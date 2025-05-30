
Dataplex: Convert Cloud Storage File Format template
---
A pipeline that converts file format of Cloud Storage files, registering metadata
for the newly created files in Dataplex.



:bulb: This is a generated documentation based
on [Metadata Annotations](https://github.com/GoogleCloudPlatform/DataflowTemplates#metadata-annotations)
. Do not change this file directly.

## Parameters

### Required parameters

* **inputAssetOrEntitiesList**: Dataplex asset or Dataplex entities that contain the input files. Format: projects/<name>/locations/<loc>/lakes/<lake-name>/zones/<zone-name>/assets/<asset name> OR projects/<name>/locations/<loc>/lakes/<lake-name>/zones/<zone-name>/entities/<entity 1 name>,projects/<name>/locations/<loc>/lakes/<lake-name>/zones/<zone-name>/entities/<entity 2 name>... .
* **outputFileFormat**: Output file format in Cloud Storage. Format: PARQUET or AVRO.
* **outputAsset**: Name of the Dataplex asset that contains Cloud Storage bucket where output files will be put into. Format: projects/<name>/locations/<loc>/lakes/<lake-name>/zones/<zone-name>/assets/<asset name>.

### Optional parameters

* **outputFileCompression**: Output file compression. Format: UNCOMPRESSED, SNAPPY, GZIP, or BZIP2. BZIP2 not supported for PARQUET files. Defaults to: SNAPPY.
* **writeDisposition**: Specifies the action that occurs if a destination file already exists. Format: OVERWRITE, FAIL, SKIP. If SKIP, only files that don't exist in the destination directory will be processed. If FAIL and at least one file already exists, no data will be processed and an error will be produced. Defaults to: SKIP.
* **updateDataplexMetadata**: Whether to update Dataplex metadata for the newly created entities. Only supported for Cloud Storage destination. If enabled, the pipeline will automatically copy the schema from source to the destination Dataplex entities, and the automated Dataplex Discovery won't run for them. Use this flag in cases where you have managed schema at the source. Defaults to: false.



## Getting Started

### Requirements

* Java 17
* Maven
* [gcloud CLI](https://cloud.google.com/sdk/gcloud), and execution of the
  following commands:
  * `gcloud auth login`
  * `gcloud auth application-default login`

:star2: Those dependencies are pre-installed if you use Google Cloud Shell!

[![Open in Cloud Shell](http://gstatic.com/cloudssh/images/open-btn.svg)](https://console.cloud.google.com/cloudshell/editor?cloudshell_git_repo=https%3A%2F%2Fgithub.com%2FGoogleCloudPlatform%2FDataflowTemplates.git&cloudshell_open_in_editor=v2/dataplex/src/main/java/com/google/cloud/teleport/v2/templates/DataplexFileFormatConversion.java)

### Templates Plugin

This README provides instructions using
the [Templates Plugin](https://github.com/GoogleCloudPlatform/DataflowTemplates#templates-plugin).

### Building Template

This template is a Flex Template, meaning that the pipeline code will be
containerized and the container will be executed on Dataflow. Please
check [Use Flex Templates](https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates)
and [Configure Flex Templates](https://cloud.google.com/dataflow/docs/guides/templates/configuring-flex-templates)
for more information.

#### Staging the Template

If the plan is to just stage the template (i.e., make it available to use) by
the `gcloud` command or Dataflow "Create job from template" UI,
the `-PtemplatesStage` profile should be used:

```shell
export PROJECT=<my-project>
export BUCKET_NAME=<bucket-name>

mvn clean package -PtemplatesStage  \
-DskipTests \
-DprojectId="$PROJECT" \
-DbucketName="$BUCKET_NAME" \
-DstagePrefix="templates" \
-DtemplateName="Dataplex_File_Format_Conversion" \
-f v2/dataplex
```


The command should build and save the template to Google Cloud, and then print
the complete location on Cloud Storage:

```
Flex Template was staged! gs://<bucket-name>/templates/flex/Dataplex_File_Format_Conversion
```

The specific path should be copied as it will be used in the following steps.

#### Running the Template

**Using the staged template**:

You can use the path above run the template (or share with others for execution).

To start a job with the template at any time using `gcloud`, you are going to
need valid resources for the required parameters.

Provided that, the following command line can be used:

```shell
export PROJECT=<my-project>
export BUCKET_NAME=<bucket-name>
export REGION=us-central1
export TEMPLATE_SPEC_GCSPATH="gs://$BUCKET_NAME/templates/flex/Dataplex_File_Format_Conversion"

### Required
export INPUT_ASSET_OR_ENTITIES_LIST=<inputAssetOrEntitiesList>
export OUTPUT_FILE_FORMAT=<outputFileFormat>
export OUTPUT_ASSET=<outputAsset>

### Optional
export OUTPUT_FILE_COMPRESSION=SNAPPY
export WRITE_DISPOSITION=SKIP
export UPDATE_DATAPLEX_METADATA=false

gcloud dataflow flex-template run "dataplex-file-format-conversion-job" \
  --project "$PROJECT" \
  --region "$REGION" \
  --template-file-gcs-location "$TEMPLATE_SPEC_GCSPATH" \
  --parameters "inputAssetOrEntitiesList=$INPUT_ASSET_OR_ENTITIES_LIST" \
  --parameters "outputFileFormat=$OUTPUT_FILE_FORMAT" \
  --parameters "outputFileCompression=$OUTPUT_FILE_COMPRESSION" \
  --parameters "outputAsset=$OUTPUT_ASSET" \
  --parameters "writeDisposition=$WRITE_DISPOSITION" \
  --parameters "updateDataplexMetadata=$UPDATE_DATAPLEX_METADATA"
```

For more information about the command, please check:
https://cloud.google.com/sdk/gcloud/reference/dataflow/flex-template/run


**Using the plugin**:

Instead of just generating the template in the folder, it is possible to stage
and run the template in a single command. This may be useful for testing when
changing the templates.

```shell
export PROJECT=<my-project>
export BUCKET_NAME=<bucket-name>
export REGION=us-central1

### Required
export INPUT_ASSET_OR_ENTITIES_LIST=<inputAssetOrEntitiesList>
export OUTPUT_FILE_FORMAT=<outputFileFormat>
export OUTPUT_ASSET=<outputAsset>

### Optional
export OUTPUT_FILE_COMPRESSION=SNAPPY
export WRITE_DISPOSITION=SKIP
export UPDATE_DATAPLEX_METADATA=false

mvn clean package -PtemplatesRun \
-DskipTests \
-DprojectId="$PROJECT" \
-DbucketName="$BUCKET_NAME" \
-Dregion="$REGION" \
-DjobName="dataplex-file-format-conversion-job" \
-DtemplateName="Dataplex_File_Format_Conversion" \
-Dparameters="inputAssetOrEntitiesList=$INPUT_ASSET_OR_ENTITIES_LIST,outputFileFormat=$OUTPUT_FILE_FORMAT,outputFileCompression=$OUTPUT_FILE_COMPRESSION,outputAsset=$OUTPUT_ASSET,writeDisposition=$WRITE_DISPOSITION,updateDataplexMetadata=$UPDATE_DATAPLEX_METADATA" \
-f v2/dataplex
```

## Terraform

Dataflow supports the utilization of Terraform to manage template jobs,
see [dataflow_flex_template_job](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/dataflow_flex_template_job).

Terraform modules have been generated for most templates in this repository. This includes the relevant parameters
specific to the template. If available, they may be used instead of
[dataflow_flex_template_job](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/dataflow_flex_template_job)
directly.

To use the autogenerated module, execute the standard
[terraform workflow](https://developer.hashicorp.com/terraform/intro/core-workflow):

```shell
cd v2/dataplex/terraform/Dataplex_File_Format_Conversion
terraform init
terraform apply
```

To use
[dataflow_flex_template_job](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/dataflow_flex_template_job)
directly:

```terraform
provider "google-beta" {
  project = var.project
}
variable "project" {
  default = "<my-project>"
}
variable "region" {
  default = "us-central1"
}

resource "google_dataflow_flex_template_job" "dataplex_file_format_conversion" {

  provider          = google-beta
  container_spec_gcs_path = "gs://dataflow-templates-${var.region}/latest/flex/Dataplex_File_Format_Conversion"
  name              = "dataplex-file-format-conversion"
  region            = var.region
  parameters        = {
    inputAssetOrEntitiesList = "<inputAssetOrEntitiesList>"
    outputFileFormat = "<outputFileFormat>"
    outputAsset = "<outputAsset>"
    # outputFileCompression = "SNAPPY"
    # writeDisposition = "SKIP"
    # updateDataplexMetadata = "false"
  }
}
```
