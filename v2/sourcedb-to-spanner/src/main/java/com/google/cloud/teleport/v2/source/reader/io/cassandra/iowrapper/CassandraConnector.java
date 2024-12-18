/*
 * Copyright (C) 2024 Google LLC
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
package com.google.cloud.teleport.v2.source.reader.io.cassandra.iowrapper;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.session.Session;
import com.google.cloud.teleport.v2.source.reader.io.cassandra.schema.CassandraSchemaReference;
import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import org.jline.utils.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Autocloseable Connector For Cassandra. Note: This class is not Serializable as it holds an active
 * connection. {@link org.apache.beam.sdk.transforms.PTransform Ptransform} must store {@link
 * CassandraDataSource} instead and use the connector to open a connection. TODO(vardhan):
 * Optionally allow driver config from GCS file.
 */
public final class CassandraConnector implements AutoCloseable {
  private final CqlSession session;
  private static final Logger LOG = LoggerFactory.getLogger(CassandraConnector.class);

  public CassandraConnector(
      CassandraDataSource dataSource, CassandraSchemaReference schemaReference) {
    Log.info(
        "Connecting to Cassandra Source dataSource = {}, schemaReference = {}",
        dataSource,
        schemaReference);
    CqlSessionBuilder builder =
        CqlSession.builder().withConfigLoader(getDriverConfigLoader(dataSource));
    builder = setCredentials(builder, dataSource);
    if (dataSource.localDataCenter() != null) {
      builder = builder.addContactPoints(List.copyOf(dataSource.contactPoints()));
      builder = builder.withLocalDatacenter(dataSource.localDataCenter());
    }
    if (schemaReference.keyspaceName() != null) {
      builder.withKeyspace(schemaReference.keyspaceName());
    }

    this.session = builder.build();
    Log.info(
        "Connected to Cassandra Source dataSource = {}, schemaReference = {}",
        dataSource,
        schemaReference);
  }

  @VisibleForTesting
  protected static CqlSessionBuilder setCredentials(
      CqlSessionBuilder builder, CassandraDataSource cassandraDataSource) {
    if (cassandraDataSource.dbAuth() != null) {
      return builder.withAuthCredentials(
          cassandraDataSource.dbAuth().getUserName().get(),
          cassandraDataSource.dbAuth().getPassword().get());
    } else {
      return builder;
    }
  }

  @VisibleForTesting
  protected static DriverConfigLoader getDriverConfigLoader(CassandraDataSource dataSource) {
    ProgrammaticDriverConfigLoaderBuilder driverConfigLoaderBuilder =
        DriverConfigLoader.programmaticBuilder()
            .withString(
                DefaultDriverOption.REQUEST_CONSISTENCY, dataSource.consistencyLevel().name())
            .withClass(DefaultDriverOption.RETRY_POLICY_CLASS, dataSource.retryPolicy());
    if (dataSource.connectTimeout() != null) {
      driverConfigLoaderBuilder =
          driverConfigLoaderBuilder.withDuration(
              DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, dataSource.connectTimeout());
    }
    if (dataSource.requestTimeout() != null) {
      driverConfigLoaderBuilder =
          driverConfigLoaderBuilder.withDuration(
              DefaultDriverOption.REQUEST_TIMEOUT, dataSource.requestTimeout());
    }
    return driverConfigLoaderBuilder.build();
  }

  public Session getSession() {
    return this.session;
  }

  /**
   * Closes this stream and releases any system resources associated with it. If the stream is
   * already closed then invoking this method has no effect.
   */
  @Override
  public void close() {
    this.session.close();
  }
}