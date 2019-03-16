package io.openems.edge.controller.io.fixdigitaloutput;

import java.util.Arrays;
import java.util.stream.Stream;

import io.openems.edge.common.channel.internal.AbstractReadChannel;
import io.openems.edge.common.channel.internal.StateChannel;
import io.openems.edge.common.channel.internal.StateCollectorChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public class Utils {
	public static Stream<? extends AbstractReadChannel<?>> initializeChannels(FixDigitalOutput c) {
		return Stream.of(//
				Arrays.stream(OpenemsComponent.ChannelId.values()).map(channelId -> {
					switch (channelId) {
					case STATE:
						return new StateCollectorChannel(c, channelId);
					}
					return null;
				}), //
				Arrays.stream(Controller.ChannelId.values()).map(channelId -> {
					switch (channelId) {
					case RUN_FAILED:
						return new StateChannel(c, channelId);
					}
					return null;
				}) //
		).flatMap(channel -> channel);
	}
}
