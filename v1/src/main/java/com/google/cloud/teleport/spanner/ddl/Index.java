/*
 * Copyright (C) 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.spanner.ddl;

import static com.google.cloud.teleport.spanner.common.NameUtils.quoteIdentifier;

import com.google.auto.value.AutoValue;
import com.google.cloud.spanner.Dialect;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Serializable;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Cloud Spanner index definition. */
@AutoValue
public abstract class Index implements Serializable {

  private static final long serialVersionUID = 7435575480487550039L;

  abstract String name();

  abstract Dialect dialect();

  abstract String table();

  abstract ImmutableList<IndexColumn> indexColumns();

  @Nullable
  abstract ImmutableList<String> options();

  abstract boolean unique();

  // restricted for gsql
  abstract boolean nullFiltered();

  @Nullable
  abstract String filter();

  @Nullable
  abstract String interleaveIn();

  @Nullable
  abstract String type();

  @Nullable
  abstract ImmutableList<String> partitionBy();

  @Nullable
  abstract ImmutableList<String> orderBy();

  public static Builder builder(Dialect dialect) {
    return new AutoValue_Index.Builder().dialect(dialect).nullFiltered(false).unique(false);
  }

  public static Builder builder() {
    return builder(Dialect.GOOGLE_STANDARD_SQL);
  }

  public void prettyPrint(Appendable appendable) throws IOException {
    switch (dialect()) {
      case GOOGLE_STANDARD_SQL:
        prettyPrintGsql(appendable);
        break;
      case POSTGRESQL:
        prettyPrintPg(appendable);
        break;
      default:
        throw new IllegalArgumentException(String.format("Unrecognized dialect: %s", dialect()));
    }
  }

  private void prettyPrintPg(Appendable appendable) throws IOException {
    appendable.append("CREATE");
    if (type() != null && (type().equals("SEARCH"))) {
      appendable.append(" " + type());
    } else if (unique()) {
      appendable.append(" UNIQUE");
    }
    appendable
        .append(" INDEX ")
        .append(quoteIdentifier(name(), dialect()))
        .append(" ON ")
        .append(quoteIdentifier(table(), dialect()));

    if (type() != null && "ScaNN".equals(type())) {
      appendable.append(" USING ScaNN ");
    }

    String indexColumnsString =
        indexColumns().stream()
            .filter(c -> c.order() != IndexColumn.Order.STORING)
            .map(c -> c.prettyPrint())
            .collect(Collectors.joining(", "));
    appendable.append("(").append(indexColumnsString).append(")");

    String storingString =
        indexColumns().stream()
            .filter(c -> c.order() == IndexColumn.Order.STORING)
            .map(c -> quoteIdentifier(c.name(), dialect()))
            .collect(Collectors.joining(", "));

    if (!storingString.isEmpty()) {
      appendable.append(" INCLUDE (").append(storingString).append(")");
    }

    if (partitionBy() != null) {
      String partitionByString =
          partitionBy().stream()
              .map(c -> quoteIdentifier(c, dialect()))
              .collect(Collectors.joining(","));

      if (!partitionByString.isEmpty()) {
        appendable.append(" PARTITION BY ").append(partitionByString);
      }
    }

    if (orderBy() != null) {
      String orderByString =
          orderBy().stream()
              .map(c -> quoteIdentifier(c, dialect()))
              .collect(Collectors.joining(","));

      if (!orderByString.isEmpty()) {
        appendable.append(" ORDER BY ").append(orderByString);
      }
    }

    if (interleaveIn() != null) {
      appendable.append(" INTERLEAVE IN ").append(quoteIdentifier(interleaveIn(), dialect()));
    }

    if (options() != null) {
      String optionsString = String.join(",", options());
      if (!optionsString.isEmpty()) {
        appendable.append(" WITH (").append(optionsString).append(")");
      }
    }

    if (filter() != null && !filter().isEmpty()) {
      appendable.append(" WHERE ").append(filter());
    }
  }

  private void prettyPrintGsql(Appendable appendable) throws IOException {
    appendable.append("CREATE");
    if (type() != null && (type().equals("SEARCH") || type().equals("VECTOR"))) {
      appendable.append(" " + type());
    } else if (unique()) {
      appendable.append(" UNIQUE");
    }
    if (nullFiltered()) {
      appendable.append(" NULL_FILTERED");
    }
    appendable
        .append(" INDEX ")
        .append(quoteIdentifier(name(), dialect()))
        .append(" ON ")
        .append(quoteIdentifier(table(), dialect()));

    String indexColumnsString =
        indexColumns().stream()
            .filter(c -> c.order() != IndexColumn.Order.STORING)
            .map(c -> c.prettyPrint())
            .collect(Collectors.joining(", "));
    appendable.append("(").append(indexColumnsString).append(")");

    String storingString =
        indexColumns().stream()
            .filter(c -> c.order() == IndexColumn.Order.STORING)
            .map(c -> quoteIdentifier(c.name(), dialect()))
            .collect(Collectors.joining(", "));

    if (!storingString.isEmpty()) {
      appendable.append(" STORING (").append(storingString).append(")");
    }

    if (partitionBy() != null) {
      String partitionByString =
          partitionBy().stream()
              .map(c -> quoteIdentifier(c, dialect()))
              .collect(Collectors.joining(","));

      if (!partitionByString.isEmpty()) {
        appendable.append(" PARTITION BY ").append(partitionByString);
      }
    }

    if (orderBy() != null) {
      String orderByString =
          orderBy().stream()
              .map(c -> quoteIdentifier(c, dialect()))
              .collect(Collectors.joining(","));

      if (!orderByString.isEmpty()) {
        appendable.append(" ORDER BY ").append(orderByString);
      }
    }

    if (interleaveIn() != null) {
      appendable.append(", INTERLEAVE IN ").append(quoteIdentifier(interleaveIn(), dialect()));
    }

    if (!nullFiltered() && filter() != null && !filter().isEmpty()) {
      appendable.append(" WHERE ").append(filter());
    }

    if (options() != null) {
      String optionsString = String.join(",", options());
      if (!optionsString.isEmpty()) {
        appendable.append(" OPTIONS (").append(optionsString).append(")");
      }
    }
  }

  abstract Builder autoToBuilder();

  public Builder toBuilder() {
    Builder builder = autoToBuilder();
    for (IndexColumn column : indexColumns()) {
      builder.columnsBuilder().set(column);
    }
    return builder;
  }

  public String prettyPrint() {
    StringBuilder sb = new StringBuilder();
    try {
      prettyPrint(sb);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return prettyPrint();
  }

  /** A builder for {@link Index}. */
  @AutoValue.Builder
  public abstract static class Builder {

    private IndexColumn.IndexColumnsBuilder<Builder> columnsBuilder;

    public abstract Builder name(String name);

    public abstract Builder table(String name);

    abstract Builder dialect(Dialect dialect);

    public abstract Dialect dialect();

    abstract Builder indexColumns(ImmutableList<IndexColumn> columns);

    public IndexColumn.IndexColumnsBuilder<Builder> columns() {
      return columnsBuilder();
    }

    abstract Builder options(ImmutableList<String> options);

    public abstract Builder unique(boolean unique);

    public Builder unique() {
      return unique(true);
    }

    public abstract Builder nullFiltered(boolean nullFiltered);

    public Builder nullFiltered() {
      return nullFiltered(true);
    }

    public abstract Builder filter(String filter);

    public abstract Builder interleaveIn(String interleaveIn);

    public abstract Builder type(String type);

    public abstract Builder partitionBy(ImmutableList<String> keys);

    public abstract Builder orderBy(ImmutableList<String> keys);

    abstract Index autoBuild();

    public Index build() {
      return this.indexColumns(columnsBuilder().build()).autoBuild();
    }

    private IndexColumn.IndexColumnsBuilder<Builder> columnsBuilder() {
      if (columnsBuilder == null) {
        columnsBuilder = new IndexColumn.IndexColumnsBuilder<>(this, dialect());
      }
      return columnsBuilder;
    }
  }
}
