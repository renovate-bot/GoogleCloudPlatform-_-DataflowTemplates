/*
 * Copyright (C) 2019 Google LLC
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
package com.google.cloud.dataflow.cdc.applier;

import com.google.cloud.dataflow.cdc.applier.CdcPCollectionsFetchers.CdcPCollectionFetcher;
import com.google.cloud.dataflow.cdc.applier.CdcToBigQueryChangeApplierPipeline.CdcApplierOptions;
import com.google.cloud.teleport.metadata.Template;
import com.google.cloud.teleport.metadata.TemplateCategory;
import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.v2.common.UncaughtExceptionLogger;
import com.google.cloud.teleport.v2.options.BigQueryStorageApiStreamingOptions;
import com.google.cloud.teleport.v2.utils.BigQueryIOUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link CdcToBigQueryChangeApplierPipeline} consumes change data corresponding to changes in a
 * database. This data is consumed from a group of Pubsub topics (one topic for each table in the
 * external database); then it is processed and inserted to BigQuery.
 *
 * <p>For each table in the external database, the {@link CdcToBigQueryChangeApplierPipeline} will
 * produce two BigQuery tables:
 *
 * <p>1) One changelog table, with the full sequence of changes made to the table in the external
 * database. This table is also referred to as Staging Table, Changelog table 2) One <b>replica</b>
 * table, which is a replica of the table in the external database. This replica table is built
 * periodically by issuing MERGE statements to BigQuery that synchronize the tables using the
 * changelog table. This table is referred to as the Replica Table.
 *
 * <p>The change data is intended to be generated by a Debezium-based connector, which watches the
 * changelog from the external database, formats the data into Beam {@link Row} format, updates Data
 * Catalog with schema information for each table, and pushes the change data to PubSub.
 *
 * <p>Check out <a
 * href="https://github.com/GoogleCloudPlatform/DataflowTemplates/blob/main/v2/cdc-parent/cdc-change-applier/README_Cdc_To_BigQuery_Template.md">README</a>
 * for instructions on how to use or modify this template.
 */
@Template(
    name = "Cdc_To_BigQuery_Template",
    category = TemplateCategory.STREAMING,
    displayName = "Synchronizing CDC data to BigQuery",
    description = "A pipeline to synchronize a Change Data Capture streams to BigQuery.",
    optionsClass = CdcApplierOptions.class,
    flexContainerName = "cdc-agg",
    contactInformation = "https://cloud.google.com/support",
    documentation =
        "https://cloud.google.com/dataflow/docs/guides/templates/provided/mysql-change-data-capture-to-bigquery",
    requirements = {
      "The Debezium connector must be <a href=\"https://github.com/GoogleCloudPlatform/DataflowTemplates/tree/master/v2/cdc-parent#deploying-the-connector\">deployed</a>.",
      "The Pub/Sub messages must be serialized in a <a href=\"https://beam.apache.org/releases/javadoc/current/org/apache/beam/sdk/values/Row.html\">Beam Row</a>."
    },
    streaming = true)
public class CdcToBigQueryChangeApplierPipeline {

  public static final Integer SECONDS_PER_DAY = 24 * 60 * 60;
  public static final Integer MAX_BQ_MERGES_PER_TABLE_PER_DAY = 1000;

  public static final Long MINIMUM_UPDATE_FREQUENCY_SECONDS =
      Math.round((SECONDS_PER_DAY / MAX_BQ_MERGES_PER_TABLE_PER_DAY) * 1.10);

  private static final Logger LOG =
      LoggerFactory.getLogger(CdcToBigQueryChangeApplierPipeline.class);

  /**
   * The {@link CdcApplierOptions} class provides the custom execution options passed by the
   * executor at the command-line.
   */
  public interface CdcApplierOptions extends PipelineOptions, BigQueryStorageApiStreamingOptions {

    @TemplateParameter.Text(
        order = 1,
        optional = true,
        groupName = "Source",
        regexes = {"[,a-zA-Z0-9._-]+"},
        description = "Pub/Sub topic(s) to read from",
        helpText = "Comma-separated list of PubSub topics to where CDC data is being pushed.")
    String getInputTopics();

    void setInputTopics(String topic);

    @TemplateParameter.Text(
        order = 2,
        regexes = {"[^/]+"},
        groupName = "Source",
        description = "Input subscriptions to the template",
        helpText =
            "The comma-separated list of Pub/Sub input subscriptions to read from, in the format `<SUBSCRIPTION_NAME>,<SUBSCRIPTION_NAME>, ...`")
    String getInputSubscriptions();

    void setInputSubscriptions(String subscriptions);

    @TemplateParameter.Text(
        order = 3,
        regexes = {".+"},
        groupName = "Target",
        description = "Output BigQuery dataset for Changelog tables",
        helpText =
            "The BigQuery dataset to store the staging tables in, in the format <DATASET_NAME>.")
    String getChangeLogDataset();

    void setChangeLogDataset(String dataset);

    @TemplateParameter.Text(
        order = 4,
        regexes = {".+"},
        groupName = "Target",
        description = "Output BigQuery dataset for replica tables",
        helpText =
            "The location of the BigQuery dataset to store the replica tables in, in the format <DATASET_NAME>.")
    String getReplicaDataset();

    void setReplicaDataset(String dataset);

    @TemplateParameter.Integer(
        order = 5,
        optional = true,
        description = "Frequency to issue updates to BigQuery tables (seconds).",
        helpText =
            "The interval at which the pipeline updates the BigQuery table replicating the MySQL database.")
    Integer getUpdateFrequencySecs();

    void setUpdateFrequencySecs(Integer frequency);

    @TemplateParameter.Boolean(
        order = 6,
        optional = true,
        description = "Whether to use a single topic for all MySQL table changes.",
        helpText =
            "Set this to `true` if you configure your Debezium connector to publish all table"
                + " updates to a single topic")
    @Default.Boolean(false)
    Boolean getUseSingleTopic();

    void setUseSingleTopic(Boolean useSingleTopic);
  }

  private static PDone buildIngestionPipeline(
      String transformPrefix, CdcApplierOptions options, PCollection<Row> input) {
    return input.apply(
        String.format("%s/ApplyChangesToBigQuery", transformPrefix),
        BigQueryChangeApplier.of(
            options.getChangeLogDataset(),
            options.getReplicaDataset(),
            options.getUpdateFrequencySecs(),
            options.as(GcpOptions.class).getProject()));
  }

  /**
   * Main entry point for pipeline execution.
   *
   * @param args Command line arguments to the pipeline.
   */
  public static void main(String[] args) throws IOException {
    UncaughtExceptionLogger.register();

    CdcApplierOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(CdcApplierOptions.class);

    BigQueryIOUtils.validateBQStorageApiOptionsStreaming(options);

    run(options);
  }

  /**
   * Runs the pipeline with the supplied options.
   *
   * @param options The execution parameters to the pipeline.
   * @return The result of the pipeline execution.
   */
  private static PipelineResult run(CdcApplierOptions options) {

    if (options.getInputTopics() != null && options.getInputSubscriptions() != null) {
      throw new IllegalArgumentException(
          "Either an input topic or a subscription must be provided");
    }

    if (options.getUpdateFrequencySecs() != null
        && options.getUpdateFrequencySecs() < MINIMUM_UPDATE_FREQUENCY_SECONDS) {
      throw new IllegalArgumentException(
          "BigQuery supports at most 1,000 MERGE statements per table per day. "
              + "Please select updateFrequencySecs of 100 or more to fit this limit");
    }

    Pipeline p = Pipeline.create(options);

    CdcPCollectionFetcher pcollectionFetcher = CdcPCollectionsFetchers.create(options);

    Map<String, PCollection<Row>> pcollections = pcollectionFetcher.changelogPcollections(p);

    for (Map.Entry<String, PCollection<Row>> tableEntry : pcollections.entrySet()) {
      String branchName = tableEntry.getKey();
      PCollection<Row> singularTableChangelog = tableEntry.getValue();
      buildIngestionPipeline(branchName, options, singularTableChangelog);
    }

    // Add label to track Dataflow CDC jobs launched.
    Map<String, String> dataflowCdcLabels = new HashMap<>();
    if (p.getOptions().as(DataflowPipelineOptions.class).getLabels() != null) {
      dataflowCdcLabels.putAll(p.getOptions().as(DataflowPipelineOptions.class).getLabels());
    }
    dataflowCdcLabels.put("dataflow-cdc", "debezium-template");
    dataflowCdcLabels.put("goog-dataflow-provided-template-name", "dataflow_dbz_cdc");
    dataflowCdcLabels.put("goog-dataflow-provided-template-type", "flex");
    p.getOptions().as(DataflowPipelineOptions.class).setLabels(dataflowCdcLabels);

    PipelineResult result = p.run();
    return result;
  }
}
