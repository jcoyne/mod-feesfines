package org.folio.rest.client;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TOKEN;
import static org.folio.util.StringUtil.urlEncode;
import static org.folio.util.UuidUtil.isUuid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.rest.exception.http.HttpGetException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

public class OkapiClient {
  protected static final Logger log = LogManager.getLogger(OkapiClient.class);
  private static final int ID_BATCH_SIZE = 88;

  private static final String OKAPI_URL_HEADER = "x-okapi-url";
  protected static final ObjectMapper objectMapper = new ObjectMapper();

  private final WebClient webClient;
  private final String okapiUrl;
  private final String tenant;
  private final String token;

  public OkapiClient(Vertx vertx, Map<String, String> okapiHeaders) {
    this.webClient = WebClientProvider.getWebClient(vertx);
    okapiUrl = okapiHeaders.get(OKAPI_URL_HEADER);
    tenant = okapiHeaders.get(OKAPI_HEADER_TENANT);
    token = okapiHeaders.get(OKAPI_HEADER_TOKEN);
  }

  HttpRequest<Buffer> okapiGetAbs(String path) {
    return webClient.getAbs(okapiUrl + path)
      .putHeader(OKAPI_HEADER_TENANT, tenant)
      .putHeader(OKAPI_URL_HEADER, okapiUrl)
      .putHeader(OKAPI_HEADER_TOKEN, token)
      .putHeader(ACCEPT, APPLICATION_JSON);
  }

  HttpRequest<Buffer> okapiPostAbs(String path) {
    return webClient.postAbs(okapiUrl + path)
      .putHeader(ACCEPT, APPLICATION_JSON)
      .putHeader(OKAPI_HEADER_TENANT, tenant)
      .putHeader(OKAPI_URL_HEADER, okapiUrl)
      .putHeader(OKAPI_HEADER_TOKEN, token);
  }

  public <T> Future<T> getById(String resourcePath, String id, Class<T> objectType) {
    Optional<String> validationError = validateGetByIdArguments(resourcePath, id, objectType);
    if (validationError.isPresent()) {
      String errorMessage = validationError.get();
      log.error(errorMessage);
      return failedFuture(new IllegalArgumentException(errorMessage));
    }

    final String url = resourcePath + "/" + id;
    Promise<HttpResponse<Buffer>> promise = Promise.promise();

    long start = currentTimeMillis();

    okapiGetAbs(url).send(promise);

    return promise.future().compose(response -> {
      int responseStatus = response.statusCode();
      log.info("{} {}ms GET {}", responseStatus, currentTimeMillis() - start, resourcePath);
      if (responseStatus != 200) {
        final String errorMessage = format("Failed to get %s by ID %s. Response status code: %s",
          objectType.getSimpleName(), id, responseStatus);
        log.error(errorMessage);
        return failedFuture(new HttpGetException(url, response, objectType));
      }
      try {
        T object = objectMapper.readValue(response.bodyAsString(), objectType);
        return succeededFuture(object);
      } catch (IOException exception) {
        final String errorMessage = format("Failed to parse response from %s. Response body: %s",
          url, response.bodyAsString());
        log.error(errorMessage);
        return failedFuture(errorMessage);
      }
    });
  }

  private static <T> Optional<String> validateGetByIdArguments(String path, String id,
    Class<T> objectType) {

    String errorMessage = null;

    if (objectType == null) {
      errorMessage = "Requested object type is null";
    } else if (isBlank(path)) {
      errorMessage = "Invalid resource path for " + objectType.getSimpleName();
    } else if (!isUuid(id)) {
      errorMessage = "Invalid UUID: " + id;
    }

    return Optional.ofNullable(errorMessage);
  }

  public <T> Future<Collection<T>> getByQuery(String resourcePath, String query,
    Class<T> objectType, String collectionName, int limit) {

    long startTimeMillis = currentTimeMillis();
    String path = String.format("%s?query=%s&limit=%d", resourcePath, urlEncode(query), limit);

    return okapiGetAbs(path)
      .send()
      .compose(response -> {
        int responseStatus = response.statusCode();
        log.debug("[{}] [{}ms] GET {}", responseStatus, currentTimeMillis() - startTimeMillis,
          resourcePath);
        if (responseStatus != 200) {
          HttpGetException exception = new HttpGetException(path, response, objectType);
          log.error("GET by query failed", exception);
          return failedFuture(exception);
        }
        return succeededFuture(
          response.bodyAsJsonObject()
            .getJsonArray(collectionName)
            .stream()
            .map(JsonObject::mapFrom)
            .map(json -> json.mapTo(objectType))
            .collect(toList())
        );
      });
  }

  protected <T> Future<Collection<T>> getByIds(String path, Collection<String> ids, Class<T> objectType,
    String collectionName) {

    Collection<T> results = new ArrayList<>();

    List<String> filteredIds = ids.stream()
      .filter(StringUtils::isNotBlank)
      .collect(toList());

    if (ids.isEmpty()) {
      return succeededFuture(results);
    }

    log.info("Fetching {} {} by IDs", ids.size(), objectType.getSimpleName());
    long startTime = currentTimeMillis();

    return ListUtils.partition(filteredIds, ID_BATCH_SIZE)
      .stream()
      .map(batch -> fetchBatch(path, batch, objectType, collectionName).onSuccess(results::addAll))
      .reduce(succeededFuture(), (f1, f2) -> f1.compose(r -> f2))
      .map(results)
      .onSuccess(r -> log.info("Fetched {} {} in {} ms", results.size(), objectType.getSimpleName(),
        currentTimeMillis() - startTime));
  }

  private <T> Future<Collection<T>> fetchBatch(String resourcePath, List<String> batch,
    Class<T> objectType, String collectionName) {

    log.debug("Fetching batch of {} {}", batch.size(), objectType.getSimpleName());
    String query = String.format("id==(%s)", String.join(" or ", batch));

    return getByQuery(resourcePath, query, objectType, collectionName, batch.size());
  }
}
