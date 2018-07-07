package com.github.alexmojaki.birdseye.pycharm;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Given the end of an API URL, returns the full URL including the server location.
     */
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

    /**
     * Executes an HTTP request, notifies the user if there are errors,
     * and parses the JSON response into an instance of the response class
     * which is returned. Returns null if there is an error.
     */
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
                                    "Delete existing database tables with <code>python -m birdseye.clear_db</code>",
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
        // Don't flood the user with error messages. Only show a notification
        // when something is newly wrong.
        if (inError) {
            return;
        }

        // If we are running the server ourselves, only show an error if
        // that server has been running for at least 3 seconds, so it has a chance
        // to receive messages. This includes not showing messages if the server
        // isn't running at all, since that usually means there will already be
        // a different message about why running the server has failed.
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

    // Convenience methods for GET and POST HTTP requests

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

    // The actual API methods.
    // The little static classes are used by Gson to parse the JSON responses.
    // Since they defer to the request() method, some will return null
    // in case of an error.

    static class CallResponse {
        /*
        The JSON response looks like this:

        {
            "call": {
                "data": {
                    // fields in Call.CallData
                },
                // other fields we don't care about right now
            },

            // Similar story to "call"
            "function": {
                "data": {
                    // Call.FunctionData
                },
                // other fields we don't care about
            }
        }

        It may seem odd but it mimics the layout in the database.
         */
        static class _Call {
            Call.CallData data;
        }

        static class Function {
            Call.FunctionData data;
        }

        _Call call;
        Function function;
    }

    @Nullable CallResponse getCall(String callId) {
        return get("call/" + callId, CallResponse.class);
    }

    static class CallsByHashResponse {
        // Basic metadata about each call, used to construct a table
        List<CallMeta> calls;

        // Ranges of nodes in the calls, used to construct range markers
        // in the BirdseyeFunction
        List<Range> ranges;
        List<Range> loop_ranges;
    }

    @Nullable CallsByHashResponse listCallsByBodyHash(String hash) {
        return get("calls_by_body_hash/" + hash, CallsByHashResponse.class);
    }

    static class HashPresentItem {
        String hash;
        int count;
    }

    /**
     * Given a collection of hashes of function bodies, returns a HashPresentItem
     * for each of those hashes present in the birdseye database, with a count of
     * the number of calls to that function.
     */
    @NotNull HashPresentItem[] getBodyHashesPresent(Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return new HashPresentItem[]{};
        }
        HashPresentItem[] hashArray = post("body_hashes_present/", hashes, HashPresentItem[].class);
        if (hashArray == null) {  // error check
            return new HashPresentItem[]{};
        }
        return hashArray;
    }

}
