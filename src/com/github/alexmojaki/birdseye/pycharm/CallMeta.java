package com.github.alexmojaki.birdseye.pycharm;

import org.ocpsoft.prettytime.PrettyTime;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static com.github.alexmojaki.birdseye.pycharm.Utils.*;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

@SuppressWarnings("WeakerAccess")
class CallMeta {

    String id;
    String start_time;
    String[][] arguments;
    String return_value;
    String exception;
    String traceback;

    String argumentsList() {
        if (arguments.length == 0) {
            return "-";
        }
        return htmlList("ul",
                mapToList(
                        arguments,
                        a -> escapeHtml(String.format("%s = %s", (Object[]) a))));
    }

    String startTime() {
        Date date;
        try {
            date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(start_time);
        } catch (ParseException e) {
            return start_time;
        }

        return String.format("%s (%s)",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date),
                new PrettyTime().format(date));
    }

    public String longResult() {
        if (traceback != null) {
            return traceback;
        } else {
            return return_value;
        }
    }
}
