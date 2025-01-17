/*
 * Copyright 2021 Red Hat
 *
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
 */

package org.bf2.srs.fleetmanager.it;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.RestAssured;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.bf2.srs.fleetmanager.it.ams.AmsWireMockServer;
import org.bf2.srs.fleetmanager.it.executor.Exec;
import org.bf2.srs.fleetmanager.it.jwks.JWKSMockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Fabian Martinez
 */
public class TestInfraManager {

    static final Logger LOGGER = LoggerFactory.getLogger(TestInfraManager.class);

    private static final String TENANT_MANAGER_AUTH_ENABLED = "TENANT_MANAGER_AUTH_ENABLED";
    private static final String MAS_SSO_URL = "MAS_SSO_URL";
    private static final String MAS_SSO_REALM = "MAS_SSO_REALM";
    private static final String MAS_SSO_CLIENT_ID = "MAS_SSO_CLIENT_ID";
    private static final String MAS_SSO_CLIENT_SECRET = "MAS_SSO_CLIENT_SECRET";

    private static final String FLEET_MANAGER_JAR_PATH = "../core/target/srs-fleet-manager-core-%s-runner.jar";
    private static final String PROJECT_VERSION = System.getProperty("project.version");
    private static final String TENANT_MANAGER_MODULE_PATH = "../apicurio-registry/multitenancy/tenant-manager-api/";
    private static final String DEPLOYMENTS_CONFIG_FILE = "./src/test/resources/deployments.yaml";
    private static final String PLANS_CONFIG_FILE = "./src/test/resources/plans.yaml";

    private LinkedList<TestInfraProcess> processes = new LinkedList<>();

    private int fleetManagerPort = 8080;

    private String tenantManagerUrl = "http://localhost:8585";

    private boolean tenantManagerAuthEnabled = false;
    private AuthConfig authConfig;
    private AuthConfig tenantManagerAuthConfig;

    private static TestInfraManager instance;

    public static TestInfraManager getInstance() {
        if (instance == null) {
            instance = new TestInfraManager();
        }
        return instance;
    }

    private Map<String, String> appEnv = new HashMap<>();

    private TestInfraManager() {
        //private constructor
    }

    public String getFleetManagerUri() {
        return "http://localhost:" + fleetManagerPort;
    }

    public String getTenantManagerUri() {
        return this.tenantManagerUrl;
    }

    public boolean isTenantManagerAuthEnabled() {
        return this.tenantManagerAuthEnabled;
    }

    public AuthConfig getAuthConfig() {
        return this.authConfig;
    }

    public AuthConfig getTenantManagerAuthConfig() {
        return this.tenantManagerAuthConfig;
    }

    public boolean isRunning() {
        return !processes.isEmpty();
    }

    /**
     * Method for starting the registry from a runner jar file. New process is created.
     */
    public void start() throws Exception {
        if (!processes.isEmpty()) {
            throw new IllegalStateException("Registry is already running");
        }

        appEnv.put("LOG_LEVEL", "DEBUG");
        appEnv.put("SRS_LOG_LEVEL", "DEBUG");

        var authConfig = runKeycloakMock();

        var amsUrl = runAmsMock();

        this.authConfig = authConfig;

        appEnv.put("AUTH_ENABLED", "true");
        appEnv.put("KEYCLOAK_URL", authConfig.keycloakUrl);
        appEnv.put("KEYCLOAK_REALM", authConfig.realm);
        appEnv.put("KEYCLOAK_API_CLIENT_ID", authConfig.clientId);
        appEnv.put("AMS_SSO_ENABLED", "false");

        String tenantManagerAuthEnabledVar = System.getenv(TENANT_MANAGER_AUTH_ENABLED);
        tenantManagerAuthEnabled = tenantManagerAuthEnabledVar != null && tenantManagerAuthEnabledVar.equals("true");

        //TODO adapt tests to work with and without authentication
        if (tenantManagerAuthEnabled) {
            LOGGER.info("Tenant Manager authentication is enabled");

            tenantManagerAuthConfig = new AuthConfig();
            tenantManagerAuthConfig.keycloakUrl = getMandatoryEnvVar(MAS_SSO_URL);
            tenantManagerAuthConfig.realm = getMandatoryEnvVar(MAS_SSO_REALM);
            tenantManagerAuthConfig.tokenEndpoint = tenantManagerAuthConfig.keycloakUrl + "/realms/"
                    + tenantManagerAuthConfig.realm + "/protocol/openid-connect/token";
            tenantManagerAuthConfig.clientId = getMandatoryEnvVar(MAS_SSO_CLIENT_ID);
            tenantManagerAuthConfig.clientSecret = getMandatoryEnvVar(MAS_SSO_CLIENT_SECRET);

            appEnv.put(TENANT_MANAGER_AUTH_ENABLED, "true");
            appEnv.put("TENANT_MANAGER_AUTH_SERVER_URL", tenantManagerAuthConfig.keycloakUrl);
            appEnv.put("TENANT_MANAGER_AUTH_SERVER_REALM", tenantManagerAuthConfig.realm);
            appEnv.put("TENANT_MANAGER_AUTH_CLIENT_ID", tenantManagerAuthConfig.clientId);
            appEnv.put("TENANT_MANAGER_AUTH_SECRET", tenantManagerAuthConfig.clientSecret);

        } else {
            appEnv.put(TENANT_MANAGER_AUTH_ENABLED, "false");
        }

        runTenantManager(tenantManagerAuthEnabled);

        String datasourceUrl = deployPostgresql("fleet-manager");
        appEnv.put("SERVICE_API_DATASOURCE_URL", datasourceUrl);
        appEnv.put("SERVICE_API_DATASOURCE_USERNAME", "postgres");
        appEnv.put("SERVICE_API_DATASOURCE_PASSWORD", "postgres");

        //set static deployments config file
        appEnv.put("REGISTRY_DEPLOYMENTS_CONFIG_FILE", DEPLOYMENTS_CONFIG_FILE);

        //set static plans config file
        appEnv.put("REGISTRY_QUOTA_PLANS_CONFIG_FILE", PLANS_CONFIG_FILE);
        appEnv.put("REGISTRY_QUOTA_PLANS_DEFAULT", "basic");


        appEnv.put("AMS_URL", amsUrl);


        Map<String, String> node1Env = new HashMap<>(appEnv);
        runFleetManager(node1Env, "node-1", fleetManagerPort);

        int c2port = fleetManagerPort + 1;

        Map<String, String> node2Env = new HashMap<>(appEnv);
        runFleetManager(node2Env, "node-2", c2port);


        RestAssured.baseURI = getFleetManagerUri();
    }

    private AuthConfig runKeycloakMock() {

        AuthConfig authConfig = new AuthConfig();

        JWKSMockServer mock = new JWKSMockServer();
        String baseUrl = mock.start();
        authConfig.keycloakUrl = baseUrl + "/auth";
        authConfig.realm = "test";
        authConfig.tokenEndpoint = authConfig.keycloakUrl + "/realms/" + authConfig.realm + "/protocol/openid-connect/token";
        authConfig.clientId = "fleet-manager-client-id";

        LOGGER.info("keycloak mock running at {}", authConfig.tokenEndpoint);

        processes.add(new EmbeddedTestInfraProcess() {

            @Override
            public String getName() {
                return "keycloak-mock";
            }

            @Override
            public void close() throws Exception {
                mock.stop();
            }

        });

        return authConfig;
    }

    private String runAmsMock() throws JsonProcessingException {

        AmsWireMockServer mock = new AmsWireMockServer();
        String baseUrl = mock.start();

        LOGGER.info("ams mock running at {}", baseUrl);

        processes.add(new EmbeddedTestInfraProcess() {

            @Override
            public String getName() {
                return "ams-mock";
            }

            @Override
            public void close() throws Exception {
                mock.stop();
            }

        });
        return baseUrl;
    }

    private String deployPostgresql(String name) throws IOException {
        EmbeddedPostgres database = EmbeddedPostgres
                .builder()
                .start();

        String datasourceUrl = database.getJdbcUrl("postgres", "postgres");

        processes.add(new EmbeddedTestInfraProcess() {

            @Override
            public String getName() {
                return "postgresql-" + name;
            }

            @Override
            public void close() throws Exception {
                database.close();
            }

        });

        return datasourceUrl;
    }

    //TODO replace tenant manager with mock?
    private void runTenantManager(boolean authEnabled) throws IOException {

        Map<String, String> appEnv = new HashMap<>();

        if (authEnabled) {
            appEnv.put("AUTH_ENABLED", "true");
            appEnv.put("KEYCLOAK_URL", getMandatoryEnvVar(MAS_SSO_URL));
            appEnv.put("KEYCLOAK_REALM", getMandatoryEnvVar(MAS_SSO_REALM));
            appEnv.put("KEYCLOAK_API_CLIENT_ID", getMandatoryEnvVar(MAS_SSO_CLIENT_ID));
        }

        String datasourceUrl = deployPostgresql("tenant-manager");

        appEnv.put("DATASOURCE_URL", datasourceUrl);
        appEnv.put("DATASOURCE_USERNAME", "postgres");
        appEnv.put("DATASOURCE_PASSWORD", "postgres");

        //registry is not deployed in purpose, it may still work
        appEnv.put("REGISTRY_ROUTE_URL", "http://localhost:3888");

        appEnv.put("LOG_LEVEL", "DEBUG");

        String path = getTenantManagerJarPath();
        LOGGER.info("Starting Tenant Manager app from: {}", path);

        Exec executor = new Exec();
        CompletableFuture.supplyAsync(() -> {
            try {

                List<String> cmd = new ArrayList<>();
                cmd.add("java");
                cmd.addAll(Arrays.asList(
                        "-jar", path));
                int timeout = executor.execute(cmd, appEnv);
                return timeout == 0;
            } catch (Exception e) {
                LOGGER.error("Failed to start tenant manager (could not find runner JAR).", e);
                System.exit(1);
                return false;
            }
        }, runnable -> new Thread(runnable).start());

        processes.add(new TestInfraProcess() {

            @Override
            public String getName() {
                return "tenant-manager";
            }

            @Override
            public void close() throws Exception {
                executor.stop();
            }

            @Override
            public String getStdOut() {
                return executor.stdOut();
            }

            @Override
            public String getStdErr() {
                return executor.stdErr();
            }

            @Override
            public boolean isContainer() {
                return false;
            }

        });

        Awaitility.await("Tenant Manager is reachable").atMost(45, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
                .until(() -> HttpUtils.isReachable("localhost", 8585, "Tenant Manager"));

        Awaitility.await("Tenant Manager is ready").atMost(45, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
                .until(() -> HttpUtils.isReady(this.tenantManagerUrl, "/q/health/ready", false, "Tenant Manager"));
    }

    private void runFleetManager(Map<String, String> appEnv, String nameSuffix, int port) throws IOException {
        appEnv.put("QUARKUS_HTTP_PORT", String.valueOf(port));
        String path = getFleetManagerJarPath();
        Exec executor = new Exec();
        LOGGER.info("Starting srs-fleet-manager app from: {}", path);
        CompletableFuture.supplyAsync(() -> {
            try {

                List<String> cmd = new ArrayList<>();
                cmd.add("java");
                cmd.addAll(Arrays.asList("-jar", path));
                int timeout = executor.execute(cmd, appEnv);
                return timeout == 0;
            } catch (Exception e) {
                LOGGER.error("Failed to start fleet manager (could not find runner JAR).", e);
                System.exit(1);
                return false;
            }
        }, runnable -> new Thread(runnable).start());
        processes.add(new TestInfraProcess() {

            @Override
            public String getName() {
                return "fleet-manager-" + nameSuffix;
            }

            @Override
            public void close() throws Exception {
                executor.stop();
            }

            @Override
            public String getStdOut() {
                return executor.stdOut();
            }

            @Override
            public String getStdErr() {
                return executor.stdErr();
            }

            @Override
            public boolean isContainer() {
                return false;
            }

        });

        try {
            Awaitility.await("fleet manager is reachable on port " + port).atMost(90, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> HttpUtils.isReachable("localhost", port, "fleet manager"));

            Awaitility.await("fleet manager is ready on port " + port).atMost(90, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS)
                    .until(() -> HttpUtils.isReady("http://localhost:" + port, "/q/health/ready", false, "fleet manager"));
        } catch (ConditionTimeoutException ex) {
            for (String s : executor.stdOut().split("\n")) {
                LOGGER.info("[STDOUT] {}", s);
            }
            for (String s : executor.stdErr().split("\n")) {
                LOGGER.info("[STDERR] {}", s);
            }
            throw ex;
        }
    }

    private String getFleetManagerJarPath() throws IOException {
        String path = String.format(FLEET_MANAGER_JAR_PATH, PROJECT_VERSION);
        LOGGER.info("Checking runner JAR path: " + path);
        if (!runnerExists(path)) {
            LOGGER.info("No runner JAR found.");
            throw new IllegalStateException("Could not find the executable jar for the server at '" + path + "'. " +
                    "This may happen if you are using an IDE to debug. Try to build the jars manually before running the tests.");
        }
        return path;
    }

    private boolean runnerExists(String path) throws IOException {
        if (path == null) {
            return false;
        }
        File file = new File(path);
        return file.isFile();
    }

    public void stopAndCollectLogs(Class<?> testClass, String testName) throws IOException {
        Path logsPath = Paths.get("target/logs/", testClass.getName(), testName);

        LOGGER.info("Stopping testing infrastructure");
        final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        String currentDate = simpleDateFormat.format(Calendar.getInstance().getTime());
        if (logsPath != null) {
            Files.createDirectories(logsPath);
        }

        processes.descendingIterator().forEachRemaining(p -> {
            //non containerized processes have to be stopped before being able to read log output
            if (!p.isContainer()) {
                try {
                    p.close();
                    Thread.sleep(3000);
                } catch (Exception e) {
                    LOGGER.error("Error stopping process " + p.getName(), e);
                }
            }
            if (logsPath != null && p.hasLogs()) {
                try {
                    Path filePath = logsPath.resolve(currentDate + "-" + p.getName() + "-" + "stdout.log");
                    LOGGER.info("Storing registry logs to " + filePath.toString());
                    Files.write(filePath, p.getStdOut().getBytes(StandardCharsets.UTF_8));
                    String stdErr = p.getStdErr();
                    if (stdErr != null && !stdErr.isEmpty()) {
                        Path stderrFile = logsPath.resolve(currentDate + "-" + p.getName() + "-" + "stderr.log");
                        Files.write(stderrFile, stdErr.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            if (p.isContainer()) {
                try {
                    p.close();
                } catch (Exception e) {
                    LOGGER.error("Error stopping process " + p.getName(), e);
                }
            }
        });
        processes.clear();
    }

    public void stop(String processName) {
        // TODO Refactor everything...
        Iterator<TestInfraProcess> iterator = processes.iterator();
        while (iterator.hasNext()) {
            TestInfraProcess p = iterator.next();
            if (!processName.equals(p.getName()))
                continue;
            LOGGER.info("Stopping infra process: {}", p.getName());
            if (!p.isContainer()) {
                try {
                    p.close();
                    Thread.sleep(3000);
                    LOGGER.info("Infra process stopped: {}", p.getName());
                } catch (Exception e) {
                    LOGGER.error("Error stopping process " + p.getName(), e);
                }
            }
            if (p.isContainer()) {
                try {
                    p.close();
                } catch (Exception e) {
                    LOGGER.error("Error stopping process " + p.getName(), e);
                }
            }
            iterator.remove();
        }
    }

    private String getTenantManagerJarPath() {
        LOGGER.info("Attempting to find tenant manager runner. Starting at cwd: " + new File("").getAbsolutePath());
        String config = System.getenv("TENANT_MANAGER_MODULE_PATH");
        if (config != null) {
            return findRunner(new File(config), "jar");
        }
        return findRunner(findTenantManagerModuleDir(), "jar");
    }

    private File findTenantManagerModuleDir() {
        File file = new File(TENANT_MANAGER_MODULE_PATH);
        if (file.isDirectory()) {
            return file;
        }
        throw new IllegalStateException("Unable to locate tenant manager module");
    }

    private String findRunner(File mavenModuleDir, String extension) {
        File targetDir = new File(mavenModuleDir, "target");
        if (targetDir.isDirectory()) {
            File[] files = targetDir.listFiles();
            for (File file : files) {
                if (extension != null) {
                    if (file.getName().contains("-runner") && file.getName().endsWith("." + extension)) {
                        return file.getAbsolutePath();
                    }
                } else if (file.getName().endsWith("-runner")) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private String getMandatoryEnvVar(String envVar) {
        String var = System.getenv().get(envVar);
        if (var == null || var.isEmpty()) {
            throw new IllegalStateException("missing " + envVar + " env var");
        }
        return var;
    }

    public void restartFleetManager() throws IOException {
        // TODO Do this better...
        stop("fleet-manager-node-1");
        stop("fleet-manager-node-2");

        Map<String, String> node1Env = new HashMap<>(appEnv);
        runFleetManager(node1Env, "node-1", fleetManagerPort);

        int c2port = fleetManagerPort + 1;

        Map<String, String> node2Env = new HashMap<>(appEnv);
        runFleetManager(node2Env, "node-2", c2port);


        RestAssured.baseURI = getFleetManagerUri();
    }
}
