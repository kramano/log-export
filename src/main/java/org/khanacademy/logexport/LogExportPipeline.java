package org.khanacademy.logexport;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.logging.v2.model.LogEntry;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.BigQueryIO.Write.CreateDisposition;
import com.google.cloud.dataflow.sdk.io.BigQueryIO.Write.WriteDisposition;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.options.*;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.ParDo;

import java.lang.IllegalArgumentException;

/**
 * Entry point for the Log Export process.
 */
public class LogExportPipeline {
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();

    @Description("Options that configure the Log Export pipeline.")
    public interface LogExportOptions extends PipelineOptions {
        @Description("Google Cloud Pub/Sub subscription to read from. Specify either this or " +
                "topic, but not both. Reading from a long-lived subscription is ideal for " +
                "production jobs since subscriptions don't send duplicate events when there are " +
                "multiple readers (e.g. when two instances of the job are running at once) and " +
                "don't drop events when there are no readers (e.g. when transitioning the code " +
                "from one version to the next.")
        String getSubscription();
        void setSubscription(String value);

        @Description("Google Cloud Pub/Sub topic to read from. Specify either this or " +
                "subscription, but not both. A temporary subscription is automatically created " +
                "and used for this job. Use this when testing changes, since it will not " +
                "interfere with the production job.")
        String getTopic();
        void setTopic(String value);

        @Description("Whether this pipeline should run only over a bounded set of N logs. " +
                "This is useful for testing locally on a small subset of data.")
        Integer getRunBoundedOver();
        void setRunBoundedOver(Integer value);

        @Description("Fully-qualified BigQuery table name to write to.")
        @Validation.Required
        String getOutputTable();
        void setOutputTable(String value);
    }

    public static void main(String[] args) {
        PipelineOptionsFactory.register(LogExportOptions.class);

        PipelineOptions pipelineOptions =
                PipelineOptionsFactory.fromArgs(args).withValidation().create();
        LogExportOptions logExportOptions = pipelineOptions.as(LogExportOptions.class);
        PipelineOptionsValidator.validate(LogExportOptions.class, logExportOptions);

        DataflowPipelineOptions dataflowOptions = pipelineOptions.as(DataflowPipelineOptions.class);
        dataflowOptions.setStreaming(true);

        Pipeline pipeline = Pipeline.create(pipelineOptions);

        String subscription = logExportOptions.getSubscription();
        String topic = logExportOptions.getTopic();
        Integer runBound = logExportOptions.getRunBoundedOver();

        if ((subscription == null) == (topic == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of subscription and topic should be specified.");
        }

        PubsubIO.Read.Bound<String> pubSubConfig = PubsubIO.Read.named("ReadFromPubSub");
        if (subscription != null) {
            pubSubConfig = pubSubConfig.subscription(subscription);
        } else {
            pubSubConfig = pubSubConfig.topic(topic);
        }

        if (runBound != null) {
            pubSubConfig = pubSubConfig.maxNumRecords(runBound);
        }

        pipeline.apply(pubSubConfig)
                .apply(ParDo.of(new ExtractLogs()))
                .apply(BigQueryIO.Write.named("WriteToBigQuery")
                                .to(logExportOptions.getOutputTable())
                                .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                                .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
                                .withSchema(LogsExtractor.create().getSchema()));

        pipeline.run();
    }

    private static class ExtractLogs extends DoFn<String, TableRow> {
        // Logs extraction logic, initialized separately for each shard.
        private transient LogsExtractor logsExtractor;

        @Override
        public void startBundle(Context c) throws Exception {
            logsExtractor = LogsExtractor.create();
        }

        @Override
        public void processElement(ProcessContext c) throws Exception {
            String logJson = c.element();
            LogEntry parsedLog = JSON_FACTORY.fromString(logJson, LogEntry.class);
            LogEntry transformedLog = LogTransformer.transform(parsedLog);
            c.output(logsExtractor.extractLogs(transformedLog));
        }
    }
}
