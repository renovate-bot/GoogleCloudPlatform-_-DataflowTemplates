/*
 * Copyright (C) 2022 Google LLC
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
package com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery;

import static org.apache.beam.sdk.util.Preconditions.checkStateNotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.services.bigquery.model.TableRow;
import com.google.auto.value.AutoValue;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.Key.Builder;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.Options.RpcPriority;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.SpannerOptions.CallContextConfigurator;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.teleport.v2.coders.FailsafeElementCoder;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.model.Mod;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.model.TrackedSpannerColumn;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.model.TrackedSpannerTable;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.BigQueryUtils;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.SchemaUpdateUtils;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.SpannerChangeStreamsUtils;
import com.google.cloud.teleport.v2.templates.spannerchangestreamstobigquery.schemautils.SpannerToBigQueryUtils;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.common.collect.ImmutableSet;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.MethodDescriptor;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.gcp.spanner.SpannerAccessor;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.ModType;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.ValueCaptureType;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Throwables;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class {@link FailsafeModJsonToTableRowTransformer} provides methods that convert a {@link Mod}
 * JSON string wrapped in {@link FailsafeElement} to a {@link TableRow}.
 */
public final class FailsafeModJsonToTableRowTransformer {

  private static final Logger LOG =
      LoggerFactory.getLogger(FailsafeModJsonToTableRowTransformer.class);

  /**
   * Primary class for taking a {@link FailsafeElement} {@link Mod} JSON input and converting to a
   * {@link TableRow}.
   */
  public static class FailsafeModJsonToTableRow
      extends PTransform<PCollection<FailsafeElement<String, String>>, PCollectionTuple> {

    /** The tag for the main output of the transformation. */
    public TupleTag<TableRow> transformOut = new TupleTag<TableRow>() {};

    /** The tag for the dead letter output of the transformation. */
    public TupleTag<FailsafeElement<String, String>> transformDeadLetterOut =
        new TupleTag<FailsafeElement<String, String>>() {};

    private final FailsafeModJsonToTableRowOptions failsafeModJsonToTableRowOptions;

    public FailsafeModJsonToTableRow(
        FailsafeModJsonToTableRowOptions failsafeModJsonToTableRowOptions) {
      this.failsafeModJsonToTableRowOptions = failsafeModJsonToTableRowOptions;
    }

    public PCollectionTuple expand(PCollection<FailsafeElement<String, String>> input) {
      PCollectionTuple out =
          input.apply(
              ParDo.of(
                      new FailsafeModJsonToTableRowFn(
                          failsafeModJsonToTableRowOptions.getSpannerConfig(),
                          failsafeModJsonToTableRowOptions.getSpannerChangeStream(),
                          failsafeModJsonToTableRowOptions.getIgnoreFields(),
                          transformOut,
                          transformDeadLetterOut,
                          failsafeModJsonToTableRowOptions.getUseStorageWriteApi(),
                          failsafeModJsonToTableRowOptions
                              .getSpannerConfig()
                              .getRpcPriority()
                              .get()))
                  .withOutputTags(transformOut, TupleTagList.of(transformDeadLetterOut)));
      out.get(transformDeadLetterOut).setCoder(failsafeModJsonToTableRowOptions.getCoder());
      return out;
    }

    /**
     * The {@link FailsafeModJsonToTableRowFn} converts a {@link Mod} JSON string wrapped in {@link
     * FailsafeElement} to a {@link TableRow}.
     */
    public static class FailsafeModJsonToTableRowFn
        extends DoFn<FailsafeElement<String, String>, TableRow> {

      private transient SpannerAccessor spannerAccessor;
      private final SpannerConfig spannerConfig;
      private final String spannerChangeStream;
      private Map<String, TrackedSpannerTable> spannerTableByName;
      private final ImmutableSet<String> ignoreFields;
      public TupleTag<TableRow> transformOut;
      public TupleTag<FailsafeElement<String, String>> transformDeadLetterOut;
      private transient CallContextConfigurator callContextConfigurator;
      private transient boolean seenException;
      private Boolean useStorageWriteApi;
      private RpcPriority rpcPriority;
      private Dialect dialect;

      public FailsafeModJsonToTableRowFn(
          SpannerConfig spannerConfig,
          String spannerChangeStream,
          ImmutableSet<String> ignoreFields,
          TupleTag<TableRow> transformOut,
          TupleTag<FailsafeElement<String, String>> transformDeadLetterOut,
          Boolean useStorageWriteApi,
          RpcPriority rpcPriority) {
        this.spannerConfig = spannerConfig;
        this.spannerChangeStream = spannerChangeStream;
        this.transformOut = transformOut;
        this.transformDeadLetterOut = transformDeadLetterOut;
        this.ignoreFields = ignoreFields;
        this.useStorageWriteApi = useStorageWriteApi;
        this.rpcPriority = rpcPriority;
        this.dialect = getDialect(spannerConfig);
      }

      private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        spannerAccessor = SpannerAccessor.getOrCreate(spannerConfig);
        setUpCallContextConfigurator();
      }

      private void setUpCallContextConfigurator() {
        callContextConfigurator =
            new CallContextConfigurator() {
              public <ReqT, RespT> ApiCallContext configure(
                  ApiCallContext context, ReqT request, MethodDescriptor<ReqT, RespT> method) {
                return GrpcCallContext.createDefault()
                    .withCallOptions(CallOptions.DEFAULT.withDeadlineAfter(120L, TimeUnit.SECONDS));
              }
            };
      }

      @Setup
      public void setUp() {
        seenException = false;
        try {
          spannerAccessor = SpannerAccessor.getOrCreate(spannerConfig);
          spannerTableByName =
              new SpannerChangeStreamsUtils(
                      spannerAccessor.getDatabaseClient(),
                      spannerChangeStream,
                      dialect,
                      rpcPriority)
                  .getSpannerTableByName();
        } catch (RuntimeException e) {
          LOG.error(
              String.format(
                  "Caught exception when setting up FailsafeModJsonToTableRowFn, message: %s,"
                      + " cause: %s",
                  Optional.ofNullable(e.getMessage()), e.getCause()));
          throw new RuntimeException(e);
        }
        setUpCallContextConfigurator();
      }

      @Teardown
      public void tearDown() {
        spannerAccessor.close();
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        FailsafeElement<String, String> failsafeModJsonString = context.element();

        try {
          TableRow tableRow = modJsonStringToTableRow(failsafeModJsonString.getPayload());
          for (String ignoreField : ignoreFields) {
            if (tableRow.containsKey(ignoreField)) {
              tableRow.remove(ignoreField);
            }
          }
          context.output(tableRow);
        } catch (Exception e) {
          if (!seenException) {
            LOG.error(
                String.format(
                    "Caught exception when processing element and storing into dead letter queue,"
                        + " message: %s, cause: %s",
                    Optional.ofNullable(e.getMessage()), e.getCause()));
            seenException = true;
          }
          context.output(
              transformDeadLetterOut,
              FailsafeElement.of(failsafeModJsonString)
                  .setErrorMessage(e.getMessage())
                  .setStacktrace(Throwables.getStackTraceAsString(e)));
        }
      }

      private TableRow modJsonStringToTableRow(String modJsonString) {
        String deadLetterMessage =
            "check dead letter queue for unprocessed records that failed to be processed";
        ObjectNode modObjectNode = null;
        try {
          modObjectNode = (ObjectNode) new ObjectMapper().readTree(modJsonString);
        } catch (JsonProcessingException e) {
          String errorMessage =
              String.format(
                  "error parsing modJsonString input into %s; %s",
                  ObjectNode.class, deadLetterMessage);
          LOG.error(errorMessage);
          throw new RuntimeException(errorMessage, e);
        }
        for (String excludeFieldName : BigQueryUtils.getBigQueryIntermediateMetadataFieldNames()) {
          if (modObjectNode.has(excludeFieldName)) {
            modObjectNode.remove(excludeFieldName);
          }
        }

        Mod mod = null;
        try {
          mod = Mod.fromJson(modObjectNode.toString());
        } catch (IOException e) {
          String errorMessage =
              String.format(
                  "error converting %s to %s; %s", ObjectNode.class, Mod.class, deadLetterMessage);
          LOG.error(errorMessage);
          throw new RuntimeException(errorMessage, e);
        }
        String spannerTableName = mod.getTableName();
        TrackedSpannerTable spannerTable;
        com.google.cloud.Timestamp spannerCommitTimestamp =
            com.google.cloud.Timestamp.ofTimeSecondsAndNanos(
                mod.getCommitTimestampSeconds(), mod.getCommitTimestampNanos());

        // Detect schema updates (newly added tables/columns) from mod and propagate changes into
        // spannerTableByName which stores schema information by table name.
        // Not able to get schema update from DELETE mods as they have empty newValuesJson.
        if (mod.getModType() != ModType.DELETE) {
          spannerTableByName =
              SchemaUpdateUtils.updateStoredSchemaIfNeeded(
                  spannerAccessor,
                  spannerChangeStream,
                  dialect,
                  mod,
                  spannerTableByName,
                  rpcPriority);
        }

        try {
          spannerTable = checkStateNotNull(spannerTableByName.get(spannerTableName));

        } catch (IllegalStateException e) {
          String errorMessage =
              String.format(
                  "Can not find spanner table %s in spannerTableByName", spannerTableName);
          LOG.error(errorMessage);
          throw new RuntimeException(errorMessage, e);
        }

        // Set metadata fields of the tableRow.
        TableRow tableRow = new TableRow();
        BigQueryUtils.setMetadataFiledsOfTableRow(
            spannerTableName,
            mod,
            modJsonString,
            spannerCommitTimestamp,
            tableRow,
            useStorageWriteApi);
        JSONObject keysJsonObject = new JSONObject(mod.getKeysJson());
        // Set Spanner key columns of the tableRow.
        SpannerToBigQueryUtils.addSpannerPkColumnsToTableRow(
            keysJsonObject, spannerTable.getPkColumns(), tableRow);

        // Set non-key columns of the tableRow.
        SpannerToBigQueryUtils.addSpannerNonPkColumnsToTableRow(
            mod.getNewValuesJson(), spannerTable.getNonPkColumns(), tableRow, mod.getModType());

        // For "INSERT" mod, we can get all columns from mod.
        // For "DELETE" mod, we only set the key columns. For all non-key columns, we already
        // populated "null".
        if (mod.getModType() == ModType.INSERT || mod.getModType() == ModType.DELETE) {
          return tableRow;
        }

        // For "NEW_ROW" and "NEW_ROW_AND_OLD_VALUES" value capture types, we can get all columns
        // from mod.
        if (mod.getValueCaptureType() == ValueCaptureType.NEW_ROW
            || mod.getValueCaptureType() == ValueCaptureType.NEW_ROW_AND_OLD_VALUES) {
          return tableRow;
        }

        // For "UPDATE" mod, the Mod only contains the changed columns, unchanged tracked columns
        // are not included, so we need to do a snapshot read to Spanner to get the full row image
        // tracked by change stream, we want to re-read the updated columns as well to get a
        // consistent view of the whole row after the transaction is committed.
        // Note that the read can fail if the database version retention period (default to be one
        // hour) has passed the snapshot read timestamp, similar to other error cases, the pipeline
        // will put the failed mod into the retry deadletter queue, and retry it for 5 times, and
        // then eventually add the failed mod into the severe deadletter queue which won't be
        // processed by the pipeline again, users should process the severe deadletter queue
        // themselves.
        Builder keyBuilder = com.google.cloud.spanner.Key.newBuilder();
        for (TrackedSpannerColumn spannerColumn : spannerTable.getPkColumns()) {
          String spannerColumnName = spannerColumn.getName();
          if (keysJsonObject.has(spannerColumnName)) {
            SpannerChangeStreamsUtils.appendToSpannerKey(spannerColumn, keysJsonObject, keyBuilder);
          } else {
            String errorMessage =
                String.format(
                    "Caught exception when snapshot reading key column for UPDATE mod: Cannot find"
                        + " value for key column %s",
                    spannerColumnName);
            LOG.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
          }
        }

        List<TrackedSpannerColumn> spannerNonPkColumns = spannerTable.getNonPkColumns();
        List<String> spannerNonPkColumnNames =
            spannerNonPkColumns.stream()
                .map(spannerNonPkColumn -> spannerNonPkColumn.getName())
                .collect(Collectors.toList());

        int retryCount = 0;
        while (true) {
          try {
            readSpannerRow(
                spannerTable.getTableName(),
                keyBuilder.build(),
                spannerNonPkColumns,
                spannerNonPkColumnNames,
                spannerCommitTimestamp,
                tableRow);
            break;
          } catch (Exception e) {
            // Retry for maximum 3 times in case of transient error.
            if (retryCount > 3) {
              LOG.error("Caught exception from Spanner snapshot read: {}, throwing", e);
              throw e;
            } else {
              LOG.error(
                  "Caught exception from Spanner snapshot read: {}, stack trace:{} current retry"
                      + " count: {}",
                  e,
                  e.getStackTrace(),
                  retryCount);
              // Wait for 1 seconds before next retry.
              try {
                TimeUnit.SECONDS.sleep(1);
              } catch (InterruptedException ex) {
                LOG.warn(
                    String.format("Caught %s during retry: %s", InterruptedException.class, ex));
              }
              retryCount++;
            }
          }
        }

        return tableRow;
      }

      // Do a Spanner read to retrieve full row. Schema can change while the pipeline is running.
      private void readSpannerRow(
          String spannerTableName,
          com.google.cloud.spanner.Key key,
          List<TrackedSpannerColumn> spannerNonPkColumns,
          List<String> spannerNonPkColumnNames,
          com.google.cloud.Timestamp spannerCommitTimestamp,
          TableRow tableRow) {
        Options.ReadQueryUpdateTransactionOption options = Options.priority(rpcPriority);
        // Create a context that uses the custom call configuration.
        Context context =
            Context.current()
                .withValue(SpannerOptions.CALL_CONTEXT_CONFIGURATOR_KEY, callContextConfigurator);
        // Do the snapshot read in the custom context.
        context.run(
            () -> {
              try (ResultSet resultSet =
                  spannerAccessor
                      .getDatabaseClient()
                      .singleUseReadOnlyTransaction(
                          TimestampBound.ofReadTimestamp(spannerCommitTimestamp))
                      .read(
                          spannerTableName,
                          KeySet.singleKey(key),
                          spannerNonPkColumnNames,
                          options)) {
                SpannerToBigQueryUtils.spannerSnapshotRowToBigQueryTableRow(
                    resultSet, spannerNonPkColumns, tableRow);
              }
            });
      }
    }
  }

  /**
   * {@link FailsafeModJsonToTableRowOptions} provides options to initialize {@link
   * FailsafeModJsonToTableRowTransformer}.
   */
  @AutoValue
  public abstract static class FailsafeModJsonToTableRowOptions implements Serializable {
    public abstract SpannerConfig getSpannerConfig();

    public abstract String getSpannerChangeStream();

    public abstract ImmutableSet<String> getIgnoreFields();

    public abstract FailsafeElementCoder<String, String> getCoder();

    public abstract Boolean getUseStorageWriteApi();

    static Builder builder() {
      return new AutoValue_FailsafeModJsonToTableRowTransformer_FailsafeModJsonToTableRowOptions
          .Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setSpannerConfig(SpannerConfig spannerSpannerConfig);

      abstract Builder setSpannerChangeStream(String spannerChangeStream);

      abstract Builder setIgnoreFields(ImmutableSet<String> ignoreFields);

      abstract Builder setCoder(FailsafeElementCoder<String, String> coder);

      abstract Builder setUseStorageWriteApi(Boolean useStorageWriteApi);

      abstract FailsafeModJsonToTableRowOptions build();
    }
  }

  private static Dialect getDialect(SpannerConfig spannerConfig) {
    DatabaseClient databaseClient = SpannerAccessor.getOrCreate(spannerConfig).getDatabaseClient();
    return databaseClient.getDialect();
  }
}
