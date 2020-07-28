package io.azsdk.demo;

import com.azure.ai.formrecognizer.FormRecognizerAsyncClient;
import com.azure.ai.formrecognizer.FormRecognizerClientBuilder;
import com.azure.ai.formrecognizer.models.FormPage;
import com.azure.ai.formrecognizer.models.OperationResult;
import com.azure.ai.textanalytics.TextAnalyticsAsyncClient;
import com.azure.ai.textanalytics.TextAnalyticsClientBuilder;
import com.azure.ai.textanalytics.models.DocumentSentiment;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.util.polling.AsyncPollResponse;
import com.azure.core.util.polling.PollerFlux;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretAsyncClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueServiceAsyncClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.azure.storage.queue.models.QueueMessageItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class AppAsync {

  public static void main(String[] args) throws InterruptedException, IOException {
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

    QueueServiceAsyncClient qsAsyncClient = new QueueServiceClientBuilder()
        .credential(credential)
        .endpoint(queueEndpoint)
        .buildAsyncClient();

    QueueAsyncClient queueAsyncClient = qsAsyncClient.getQueueAsyncClient(queueName);

    FormRecognizerAsyncClient formRecognizerClient = new FormRecognizerClientBuilder()
        .endpoint(formRecognizerEndpoint)
        .credential(new AzureKeyCredential(dotenv.get("AZURE_FR_KEY")))
        .buildAsyncClient();

    TextAnalyticsAsyncClient textAnalyticsAsyncClient = new TextAnalyticsClientBuilder()
        .endpoint(textAnalyticsEndpoint)
        .credential(credential)
        .buildAsyncClient();

    SecretAsyncClient secretAsyncClient = new SecretClientBuilder()
        .vaultUrl(keyvaultEndpoint)
        .credential(credential)
        .buildAsyncClient();

    ObjectMapper objectMapper = new ObjectMapper();

    AtomicReference<CosmosAsyncClient> cosmosAsyncClient = new AtomicReference<>();
    AtomicReference<CosmosAsyncDatabase> cosmosDb = new AtomicReference<>();
    AtomicReference<CosmosAsyncContainer> cosmosContainer = new AtomicReference<>();

    Queue<QueueMessageItem> receivedMessages = new ConcurrentLinkedDeque<>();
    Queue<Image> imageQueue = new ConcurrentLinkedDeque<>();

    secretAsyncClient.getSecret(cosmosKeyName)
        .publishOn(Schedulers.boundedElastic())
        .flatMap(secret -> createCosmosClients(cosmosDbName, cosmosEndpoint, cosmosContainerName, cosmosAsyncClient,
            cosmosDb, cosmosContainer, secret))
        .thenMany(
            queueAsyncClient.receiveMessages(1)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(messageItem -> System.out.println("Received message " + messageItem.getMessageId()))
                .concatMap(
                    messageItem -> processQueueMessage(formRecognizerClient, objectMapper, receivedMessages, imageQueue,
                        messageItem))
                .concatMap(AppAsync::processFormRecognizerResult)
                .doOnNext(formPages -> System.out.printf("Extracted {} forms from image %n", formPages.size()))
                .concatMap(formPageList -> analyzeSentiment(textAnalyticsAsyncClient, formPageList))
                .doOnNext(imageSentiment -> System.out.println("Image sentiment is " + imageSentiment))
                .concatMap(documentSentiment -> addImageToCosmos(cosmosContainer, imageQueue, documentSentiment))
                .doOnNext(response -> System.out.println("Cosmos document saved " + response.getActivityId()))
                .concatMap(response -> deleteMessageFromQueue(queueAsyncClient, receivedMessages, response))
                .doOnError(ex -> System.out.println("Error occurred " + ex))
                .onErrorResume(ex -> Mono.empty())
                .repeat()
        )
        .subscribe(v -> {
              System.out.println("Print in subscriber " + v);
            }, ex -> ex.printStackTrace(),
            () -> System.out.println("Completed"));
    System.in.read();
  }

  @NotNull
  private static Mono<?> createCosmosClients(String cosmosDbName, String cosmosEndpoint, String cosmosContainerName,
      AtomicReference<CosmosAsyncClient> cosmosAsyncClient, AtomicReference<CosmosAsyncDatabase> cosmosDb,
      AtomicReference<CosmosAsyncContainer> cosmosContainer, KeyVaultSecret secret) {
    cosmosAsyncClient.set(new CosmosClientBuilder()
        .endpoint(cosmosEndpoint)
        .credential(new AzureKeyCredential(secret.getValue()))
        .buildAsyncClient());

    cosmosDb.set(cosmosAsyncClient.get().getDatabase(cosmosDbName));
    cosmosContainer.set(cosmosDb.get().getContainer(cosmosContainerName));
    return Mono.empty();
  }

  private static Publisher<? extends Void> deleteMessageFromQueue(QueueAsyncClient queueAsyncClient,
      Queue<QueueMessageItem> receivedMessages, CosmosItemResponse<Image> response) {

    QueueMessageItem message = receivedMessages.poll();
    return queueAsyncClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
  }

  private static Publisher<? extends CosmosItemResponse<Image>> addImageToCosmos(
      AtomicReference<CosmosAsyncContainer> cosmosContainer, Queue<Image> imageQueue,
      DocumentSentiment documentSentiment) {
    System.out.println(documentSentiment.getSentiment());
    return cosmosContainer.get().upsertItem(imageQueue.poll());
  }

  private static Publisher<? extends DocumentSentiment> analyzeSentiment(
      TextAnalyticsAsyncClient textAnalyticsAsyncClient, List<FormPage> formPageList) {
    StringBuilder sb = new StringBuilder();
    formPageList
        .stream()
        .flatMap(formPage -> formPage.getLines().stream())
        .forEach(formLine -> sb.append(formLine.getText()));
    System.out.println(sb.toString());
    String imageText = sb.toString();
    if (imageText == null || imageText.isEmpty()) {
      System.out.println("No text extracted from image");
      return Mono.empty();
    } else {
      System.out.println("Image text " + imageText);
      return textAnalyticsAsyncClient.analyzeSentiment(imageText);
    }
  }

  private static Publisher<? extends List<FormPage>> processFormRecognizerResult(
      AsyncPollResponse<OperationResult, List<FormPage>> response) {
    return response.getFinalResult();
  }

  @NotNull
  private static Publisher<? extends AsyncPollResponse<OperationResult, List<FormPage>>> processQueueMessage(
      FormRecognizerAsyncClient formRecognizerClient, ObjectMapper objectMapper,
      Queue<QueueMessageItem> receivedMessages, Queue<Image> imageQueue, QueueMessageItem messageItem) {
    Image image = null;
    try {
      image = objectMapper.readValue(messageItem.getMessageText(), Image.class);
    } catch (Exception exception) {
      return PollerFlux.empty();
    }
    if (image == null) {
      return Mono.empty();
    }
    receivedMessages.offer(messageItem);
    imageQueue.offer(image);
    System.out.println("Got image " + image.getBlobUri());
    return formRecognizerClient.beginRecognizeContentFromUrl(image.getBlobUri(),
        Duration.ofMillis(10)).last();
  }
}
