/*
 * Copyright (C) 2021 Google LLC
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
package com.google.cloud.teleport.options;

import com.google.cloud.teleport.metadata.TemplateCreationParameter;
import com.google.cloud.teleport.metadata.TemplateParameter;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.joda.time.format.DateTimeFormat;

/**
 * Provides options for shards and overriding {@link DateTimeFormat} patterns for windowed files.
 */
public interface WindowedFilenamePolicyOptions extends PipelineOptions {

  @TemplateParameter.Text(
      order = 1,
      optional = true,
      description = "Shard template",
      helpText =
          "The shard template defines the dynamic portion of each windowed file. By default, the pipeline uses a"
              + " single shard for output to the file system within each window. Therefore, all data outputs into a"
              + " single file per window. The `outputShardTemplate` defaults `to W-P-SS-of-NN`, where `W` is the window"
              + " date range, `P` is the pane info, `S` is the shard number, and `N` is the number of shards. In case of a single"
              + " file, the `SS-of-NN` portion of the `outputShardTemplate` is `00-of-01`.",
      regexes = "^W-P-(S){1,}-of-(N){1,}$")
  @Default.String("W-P-SS-of-NN")
  ValueProvider<String> getOutputShardTemplate();

  void setOutputShardTemplate(ValueProvider<String> value);

  @TemplateCreationParameter(value = "0")
  @Description(
      "The maximum number of output shards produced when writing. A higher number of shards means"
          + " higher throughput for writing to Cloud Storage, but potentially higher data"
          + " aggregation cost across shards when processing output Cloud Storage files.")
  @Default.Integer(0)
  Integer getNumShards();

  void setNumShards(Integer value);

  @TemplateCreationParameter(value = "5m")
  @Description(
      "The window duration/size in which data will be written to Cloud Storage. Allowed formats "
          + " are: Ns (for seconds, example: 5s), Nm (for minutes, example: 12m), Nh (for hours,"
          + " example: 2h).")
  @Default.String("5m")
  String getWindowDuration();

  void setWindowDuration(String value);

  @TemplateParameter.Text(
      order = 4,
      optional = true,
      regexes = {"^[^A-Za-z0-9/]?(y+|Y+)[^A-Za-z0-9/]?$"},
      description = "Custom Year Pattern to use for the output directory",
      helpText =
          "Pattern for formatting the year. Must be one or more of `y` or `Y`. Case makes no"
              + " difference in the year. Optionally, wrap the pattern with characters that"
              + " aren't alphanumeric or the directory (`/`) character. Defaults to `YYYY`.")
  ValueProvider<String> getYearPattern();

  void setYearPattern(ValueProvider<String> yearPattern);

  @TemplateParameter.Text(
      order = 5,
      optional = true,
      regexes = {"^[^A-Za-z0-9/]?(M+)[^A-Za-z0-9/]?$"},
      description = "Custom Month Pattern to use for the output directory",
      helpText =
          "Pattern for formatting the month. Must be one or more of the `M` character. "
              + "Optionally, wrap the pattern with characters that aren't alphanumeric or the "
              + "directory (`/`) character. Defaults to `MM`.")
  ValueProvider<String> getMonthPattern();

  void setMonthPattern(ValueProvider<String> monthPattern);

  @TemplateParameter.Text(
      order = 6,
      optional = true,
      regexes = {"^[^A-Za-z0-9/]?(d+|D+)[^A-Za-z0-9/]?$"},
      description = "Custom Day Pattern to use for the output directory",
      helpText =
          "Pattern for formatting the day. Must be one or more of `d` for day of month or `D` for"
              + " day of year. Optionally,"
              + " wrap the pattern with characters that aren't alphanumeric or the directory (`/`)"
              + " character. Defaults to `dd`.")
  ValueProvider<String> getDayPattern();

  void setDayPattern(ValueProvider<String> dayPattern);

  @TemplateParameter.Text(
      order = 7,
      optional = true,
      regexes = {"^[^A-Za-z0-9/]?(H+)[^A-Za-z0-9/]?$"},
      description = "Custom Hour Pattern to use for the output directory",
      helpText =
          "Pattern for formatting the hour. Must be one or more of the `H` character. Optionally,"
              + " wrap the pattern with characters that aren't alphanumeric or the directory"
              + " (`/`) character. Defaults to `HH`.")
  ValueProvider<String> getHourPattern();

  void setHourPattern(ValueProvider<String> hourPattern);

  @TemplateParameter.Text(
      order = 8,
      optional = true,
      regexes = {"^[^A-Za-z0-9/]?(m+)[^A-Za-z0-9/]?$"},
      description = "Custom Minute Pattern to use for the output directory",
      helpText =
          "Pattern for formatting the minute. Must be one or more of the `m` character. Optionally,"
              + " wrap the pattern with characters that aren't alphanumeric or the directory"
              + " (`/`) character. Defaults to `mm`.")
  ValueProvider<String> getMinutePattern();

  void setMinutePattern(ValueProvider<String> minutePattern);
}
