package com.devroom.message.application;

import java.util.UUID;

public class ChannelNotFoundException extends RuntimeException {
    public ChannelNotFoundException(UUID channelId) {
        super("Channel not found: " + channelId);
    }
}
