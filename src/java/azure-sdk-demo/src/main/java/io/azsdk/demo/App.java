package io.azsdk.demo;

import com.azure.ai.formrecognizer.FormRecognizerClient;
import com.azure.ai.formrecognizer.FormRecognizerClientBuilder;
import com.azure.ai.formrecognizer.models.FormPage;
import com.azure.ai.formrecognizer.models.OperationResult;
import com.azure.ai.textanalytics.TextAnalyticsClient;
import com.azure.ai.textanalytics.TextAnalyticsClientBuilder;
import com.azure.ai.textanalytics.models.DocumentSentiment;
import com.azure.ai.textanalytics.models.TextSentiment;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.polling.SyncPoller;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.DeviceCodeCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.azure.storage.queue.models.QueueMessageItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import java.util.List;

/**
 * Hello world!
 */
public class App {

  public static void main(String[] args) throws InterruptedException {
    System.out.println("Queue service initializing");

    Dotenv dotenv = Dotenv.configure().filename("env").systemProperties().load();

    // TokenCredential credential = new ChainedTokenCredentialBuilder()
    // .addFirst(new EnvironmentCredentialBuilder().build())
    // .addLast(new ManagedIdentityCredentialBuilder().build())
    // .build();

    // TokenCredential credential = new SharedTokenCacheCredentialBuilder()
    // .clientId("04b07795-8ddb-461a-bbee-02f9e1bf7b46")
    // .build();

     TokenCredential credential = new DefaultAzureCredentialBuilder().build();
//    TokenCredential credential = new DeviceCodeCredentialBuilder().clientId("04b07795-8ddb-461a-bbee-02f9e1bf7b46")
//        .challengeConsumer(code -> System.out.println(code.getMessage())).build();

    System.out.println("Credential is created");

    final String queueEndpoint = dotenv.get("AZURE_STORAGE_QUEUE_ENDPOINT");
    final String queueName = dotenv.get("AZURE_STORAGE_QUEUE_NAME");
    final String formRecognizerEndpoint = dotenv.get("AZURE_FORM_RECOGNIZER_ENDPOINT");
    final String textAnalyticsEndpoint = dotenv.get("AZURE_TEXT_ANALYTICS_ENDPOINT");
    final String keyvaultEndpoint = dotenv.get("AZURE_KEYVAULT_ENDPOINT");

    final String cosmosKeyName = dotenv.get("AZURE_COSMOS_KEY_NAME");
    final String cosmosDbName = dotenv.get("AZURE_COSMOS_DB");
    final String cosmosEndpoint = dotenv.get("AZURE_COSMOS_ENDPOINT");
    final String cosmosContainerName = dotenv.get("AZURE_COSMOS_CONTAINER");

    QueueServiceClient qsClient = new QueueServiceClientBuilder()
        .credential(credential)
        .endpoint(queueEndpoint)
        .buildClient();

    QueueClient queueClient = qsClient.getQueueClient(queueName);

    FormRecognizerClient formRecognizerClient = new FormRecognizerClientBuilder()
        .endpoint(formRecognizerEndpoint)
        .credential(credential)
        .buildClient();

    TextAnalyticsClient textAnalyticsClient = new TextAnalyticsClientBuilder()
        .endpoint(textAnalyticsEndpoint)
        .credential(credential)
        .buildClient();

    SecretClient secretClient = new SecretClientBuilder()
        .vaultUrl(keyvaultEndpoint)
        .credential(credential)
        .buildClient();

    KeyVaultSecret secret = secretClient.getSecret(cosmosKeyName);
    CosmosClient cosmosClient = new CosmosClientBuilder()
        .endpoint(cosmosEndpoint)
        .credential(new AzureKeyCredential(secret.getValue()))
        .buildClient();

    CosmosDatabase cosmosDb = cosmosClient.getDatabase(cosmosDbName);
    CosmosContainer cosmosContainer = cosmosDb.getContainer(cosmosContainerName);

    ObjectMapper objectMapper = new ObjectMapper();

    while (true) {
      System.out.println("Waiting for messages from queue");

      PagedIterable<QueueMessageItem> messages = queueClient.receiveMessages(10);

      messages.forEach(message -> {
        System.out.println(message.getMessageId());
        Image image = null;
        try {
          image = objectMapper.readValue(message.getMessageText(), Image.class);
        } catch (Exception ex) {

        }

        if (image == null) {
          return;
        }

        SyncPoller<OperationResult, List<FormPage>> poller = formRecognizerClient
            .beginRecognizeContentFromUrl(image.getBlobUri());

        List<FormPage> formPages = poller.getFinalResult();

        StringBuilder sb = new StringBuilder();
        formPages
            .stream()
            .flatMap(formPage -> formPage.getLines().stream())
            .forEach(formLine -> sb.append(formLine.getText()));

        System.out.println(sb.toString());
        String imageText = sb.toString();
        if (imageText == null || imageText.isEmpty()) {
          System.out.println("No text extracted from image");

        } else {
          System.out.println("Image text " + imageText);
          DocumentSentiment documentSentiment = textAnalyticsClient.analyzeSentiment(imageText);
          TextSentiment sentiment = documentSentiment.getSentiment();
          System.out.println("Image sentiment " + sentiment);
        }

        CosmosItemResponse<Image> imageCosmosItemResponse = cosmosContainer.upsertItem(image,
            new CosmosItemRequestOptions().setIfMatchETag("*"));
        System.out.println("Cosmos document saved " + imageCosmosItemResponse.getActivityId());

//        queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
//        System.out.println("Queue message deleted" + message.getMessageId());
      });

      Thread.sleep(1000);

    }
  }
}
