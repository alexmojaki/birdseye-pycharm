package com.github.alexmojaki.birdseye.pycharm;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;

class ApiClient {
    private final MyProjectComponent projectComponent;
    private boolean inError = false;

    ApiClient(MyProjectComponent projectComponent) {
        this.projectComponent = projectComponent;
    }

    private String url(String path) {
        String base = state().runServer ? ("http://localhost:" + state().port) : state().serverUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/" + path;
    }

    private State state() {
        return projectComponent.state;
    }

    private <T> T request(Request request, Class<T> responseClass) {
        try {
            HttpResponse response = request.execute().returnResponse();
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                String message = "Request returned response with code " + statusCode + ".";
                if (statusCode == 404
                        // If we're running the server, the process monitor should be able
                        // to offer an upgrade.
                        && !state().runServer) {
                    message += " You probably need to upgrade birdseye:<br>" +
                            htmlList("ol", Arrays.asList(
                                    "<code>pip install --upgrade birdseye</code>",
                                    "Delete existing database tables (e.g. delete <code>$HOME/.birdseye.db</code>).",
                                    "Restart the server."
                            ));
                }
                notifyError(message);
                return null;
            }
            String content = EntityUtils.toString(response.getEntity());
            T result = GSON.fromJson(content, responseClass);
            inError = false;
            return result;
        } catch (IOException e) {
            notifyError(e.getMessage());
            return null;
        }
    }

    private void notifyError(String message) {
        if (inError) {
            return;
        }

        if (state().runServer) {
            ProcessMonitor monitor = projectComponent.responsibleProcessMonitor();
            if (!(monitor.isRunning() && monitor.runningTime() > 3000)) {
                return;
            }
        }

        String title = "Error communicating with birdseye server";
        projectComponent.notifyError(title, message);
        inError = true;
    }

    private <T> T get(String path, Class<T> responseClass) {
        Request request = Request.Get(url(path));
        return request(request, responseClass);
    }

    @SuppressWarnings("SameParameterValue")
    private <T> T post(String path, Object body, Class<T> responseClass) {
        Request request = Request.Post(url(path))
                .bodyString(GSON.toJson(body), ContentType.APPLICATION_JSON);
        return request(request, responseClass);
    }

    static class CallResponse {
        static class _Call {
            Call.CallData data;
        }

        static class Function {
            Call.FunctionData data;
        }

        _Call call;
        Function function;
    }

    CallResponse getCall(String callId) {
        return get("call/" + callId, CallResponse.class);
    }

    static class CallsByHashResponse {
        List<CallMeta> calls;
        List<Range> ranges;
        List<Range> loop_ranges;
    }

    CallsByHashResponse listCallsByBodyHash(String hash) {
        return get("calls_by_body_hash/" + hash, CallsByHashResponse.class);
    }

    static class HashPresentItem {
        String hash;
        int count;
    }

    HashPresentItem[] getBodyHashesPresent(Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return new HashPresentItem[]{};
        }
        HashPresentItem[] hashArray = post("body_hashes_present/", hashes, HashPresentItem[].class);
        if (hashArray == null) {
            return new HashPresentItem[]{};
        }
        return hashArray;
    }

}
