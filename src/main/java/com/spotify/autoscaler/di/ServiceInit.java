/*-
 * -\-\-
 * bigtable-autoscaler
 * --
 * Copyright (C) 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.autoscaler.di;

import com.spotify.autoscaler.Autoscaler;
import com.spotify.autoscaler.client.StackdriverClient;
import com.spotify.autoscaler.db.Database;
import com.spotify.metrics.ffwd.FastForwardReporter;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.glassfish.grizzly.http.server.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceInit {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServiceInit.class);
  private final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
  private final Autoscaler autoscaler;
  private final Database database;
  private final HttpServer server;
  private final FastForwardReporter reporter;
  private final StackdriverClient stackdriverClient;
  private static final Duration RUN_INTERVAL = Duration.ofSeconds(5);

  @Inject
  public ServiceInit(
      final Autoscaler autoscaler,
      final Database database,
      final HttpServer server,
      final FastForwardReporter reporter,
      final StackdriverClient stackdriverClient) {
    this.autoscaler = autoscaler;
    this.database = database;
    this.server = server;
    this.reporter = reporter;
    this.stackdriverClient = stackdriverClient;
  }

  public void start() throws IOException {
    if (reporter != null) {
      reporter.start();
    }
    executor.scheduleWithFixedDelay(
        autoscaler, RUN_INTERVAL.toMillis(), RUN_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    server.start();
    addShutdownHooks();
  }

  private void addShutdownHooks() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    onShutdown();
                    LOGGER.info("services shutdown");
                  } catch (final Exception e) {
                    LOGGER.error("Exception occurred on shutdown", e);
                    throw new RuntimeException(e);
                  }
                }));
  }

  private void onShutdown() throws Exception {
    stackdriverClient.close();
    server.shutdown(10, TimeUnit.SECONDS).get();
    if (reporter != null) {
      reporter.stop();
    }
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    autoscaler.close();
    database.close();
  }
}
