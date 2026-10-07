import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.S3Config;
import interview.guide.common.config.StorageConfigProperties;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Read only the exact controlled object. Credentials stay in env; never print SDK exceptions. */
public class RehevoOwnedObjectHead {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) { System.exit(2); }
    UUID.fromString(args[1]);
    if (!args[0].startsWith("knowledgebases/") || !args[0].contains("durable-restart-" + args[1])
        || !(args[2].equals("EXISTS") || args[2].equals("ABSENT"))) { System.exit(2); }
    StorageConfigProperties config = new StorageConfigProperties();
    config.setEndpoint(System.getenv("APP_STORAGE_ENDPOINT"));
    URI endpoint = URI.create(config.getEndpoint());
    if (!(endpoint.getHost().equals("localhost") || endpoint.getHost().equals("127.0.0.1"))
        || endpoint.getPort() != 19087 || !endpoint.getScheme().equals("http")) { System.exit(2); }
    config.setAccessKey(System.getenv("RUSTFS_ACCESS_KEY"));
    config.setSecretKey(System.getenv("RUSTFS_SECRET_KEY"));
    config.setBucket(System.getenv("APP_STORAGE_BUCKET"));
    config.setRegion(System.getenv("APP_STORAGE_REGION"));
    config.setApiCallTimeout(Duration.ofSeconds(10));
    config.setApiCallAttemptTimeout(Duration.ofSeconds(5));
    try (var client = new S3Config(config).s3Client()) {
      int bucketStatus = client.headBucket(HeadBucketRequest.builder().bucket(config.getBucket()).build())
          .sdkHttpResponse().statusCode();
      int status;
      long length = -1;
      try {
        var response = client.headObject(HeadObjectRequest.builder().bucket(config.getBucket()).key(args[0]).build());
        status = response.sdkHttpResponse().statusCode();
        length = response.contentLength();
      } catch (S3Exception error) { status = error.statusCode(); }
      boolean passed = bucketStatus == 200 && status == (args[2].equals("EXISTS") ? 200 : 404);
      System.out.println(new ObjectMapper().writeValueAsString(Map.of("expected", args[2], "objectHttpStatus", status,
          "bucketHttpStatus", bucketStatus, "contentLength", length, "exactOwnedKeyOnly", true,
          "credentialValuesRecorded", false, "passed", passed)));
      if (!passed) { System.exit(2); }
    } catch (Exception error) {
      System.out.println(new ObjectMapper().writeValueAsString(Map.of("passed", false,
          "failureType", error.getClass().getSimpleName(), "credentialValuesRecorded", false)));
      System.exit(2);
    }
  }
}
