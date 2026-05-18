package com.devroom.message.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    private final MentionParser parser = new MentionParser();

    @Test
    void extractsAtMentions() {
        var result = parser.extract("Hej @code-reviewer kan du kolla @junior-helper också?");
        assertThat(result).containsExactly("code-reviewer", "junior-helper");
    }

    @Test
    void deduplicatesMentions() {
        var result = parser.extract("@dup @dup hello");
        assertThat(result).containsExactly("dup");
    }

    @Test
    void returnsEmptyWhenNoMentions() {
        var result = parser.extract("just text");
        assertThat(result).isEmpty();
    }
}
