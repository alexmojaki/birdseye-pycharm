package com.github.alexmojaki.birdseye.pycharm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.project.Project;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.github.alexmojaki.birdseye.pycharm.Utils.htmlList;

class ApiClient {
    private static final Gson GSON = new GsonBuilder().create();
    private final Project project;
    private boolean inError = false;

    ApiClient(Project project) {
        this.project = project;
    }

    private String url(String path) {
        State state = MyProjectComponent.getInstance(project).state;
        String base = state.runServer ? ("http://localhost:" + state.port) : state.serverUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/" + path;
    }

    private <T> T request(Request request, Class<T> responseClass) {
        try {
            HttpResponse response = request.execute().returnResponse();
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                String message = "Request returned response with code " + statusCode + ".";
                if (statusCode == 404) {
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
        MyProjectComponent component = MyProjectComponent.getInstance(project);
        if (!inError &&
                !(component.state.runServer && !
                        (component.responsibleProcessMonitor().isRunning() &&
                                component.responsibleProcessMonitor().runningTime() > 3000))) {
            String title = "Error communicating with birdseye server";
            component.notifyError(title, message);
            inError = true;
        }

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
    }

    CallsByHashResponse listCallsByBodyHash(String hash) {
        return get("calls_by_body_hash/" + hash, CallsByHashResponse.class);
    }

    static class HashPresentItem {
        String hash;
        int count;
    }

    HashPresentItem[] getBodyHashesPresent(Collection<String> hashes) {
        HashPresentItem[] hashArray = post("body_hashes_present/", hashes, HashPresentItem[].class);
        if (hashArray == null) {
            return new HashPresentItem[]{};
        }
        return hashArray;
    }

}