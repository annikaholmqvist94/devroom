package com.devroom.message.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MentionParser {

    private static final Pattern MENTION = Pattern.compile("@([a-z0-9-]+)");

    public List<String> extract(String body) {
        var result = new LinkedHashSet<String>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            result.add(m.group(1));
        }
        return List.copyOf(result);
    }
}
