package datameshmanager.databricks;

import com.databricks.sdk.AccountClient;
import com.databricks.sdk.WorkspaceClient;
import com.databricks.sdk.core.DatabricksConfig;
import datameshmanager.sdk.DataMeshManagerAssetsSynchronizer;
import datameshmanager.sdk.DataMeshManagerClient;
import datameshmanager.sdk.DataMeshManagerEventListener;
import datameshmanager.sdk.DataMeshManagerStateRepositoryRemote;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "datameshmanager")
@ConfigurationPropertiesScan("datameshmanager")
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public WorkspaceClient workspaceClient(DatabricksProperties properties) {
    var databricksConfig = new DatabricksConfig()
        .setHost(properties.workspace().host())
        .setClientId(properties.workspace().clientId())
        .setClientSecret(properties.workspace().clientSecret());
    // TODO support GCP and Azure
    return new WorkspaceClient(databricksConfig);
  }
  @Bean
  public AccountClient accountClient(DatabricksProperties properties) {
    var databricksConfig = new DatabricksConfig()
        .setHost(Objects.requireNonNullElse(properties.account().host(), "https://accounts.cloud.databricks.com"))
        .setAccountId(Objects.requireNonNull(properties.account().accountId(), "Databricks account ID is required"))
        .setClientId(properties.account().clientId())
        .setClientSecret(properties.account().clientSecret());
    return new AccountClient(databricksConfig);
  }

  @Bean
  public DataMeshManagerClient dataMeshManagerClient(
      @Value("${datameshmanager.client.host}") String host,
      @Value("${datameshmanager.client.apikey}") String apiKey) {
    return new DataMeshManagerClient(host, apiKey);
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "datameshmanager.client.databricks.accessmanagement.enabled", havingValue = "true")
  public DataMeshManagerEventListener dataMeshManagerEventListener(
      DataMeshManagerClient client, DatabricksProperties databricksProperties,
      WorkspaceClient workspaceClient,
      AccountClient accountClient,
      TaskExecutor taskExecutor) {
    var connectorid = databricksProperties.accessmanagement().connectorid();
    var eventHandler = new DatabricksAccessManagementHandler(client, workspaceClient,   accountClient);
    var stateRepository = new DataMeshManagerStateRepositoryRemote(connectorid, client);
    var dataMeshManagerEventListener = new DataMeshManagerEventListener(connectorid, "accessmanagement", client, eventHandler, stateRepository);
    taskExecutor.execute(dataMeshManagerEventListener::start);
    return dataMeshManagerEventListener;
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "datameshmanager.client.databricks.assets.enabled", havingValue = "true")
  public DataMeshManagerAssetsSynchronizer dataMeshManagerAssetsSynchronizer(
      DatabricksProperties databricksProperties,
      DataMeshManagerClient client,
      WorkspaceClient workspaceClient,
      TaskExecutor taskExecutor) {
    var connectorid = databricksProperties.assets().connectorid();
    var stateRepository = new DataMeshManagerStateRepositoryRemote(connectorid, client);
    var assetsSupplier = new DatabricksAssetsSupplier(workspaceClient, stateRepository, databricksProperties);
    var dataMeshManagerAssetsSynchronizer = new DataMeshManagerAssetsSynchronizer(connectorid, client, assetsSupplier);
    if (databricksProperties.assets().pollinterval() != null) {
      dataMeshManagerAssetsSynchronizer.setDelay(databricksProperties.assets().pollinterval());
    }

    taskExecutor.execute(dataMeshManagerAssetsSynchronizer::start);
    return dataMeshManagerAssetsSynchronizer;
  }

  @Bean
  public SimpleAsyncTaskExecutor taskExecutor() {
    return new SimpleAsyncTaskExecutor();
  }

}
