package io.openems.edge.ess.streetscooter;

import java.time.LocalDateTime;
import java.util.function.BiConsumer;

import org.slf4j.Logger;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.ess.streetscooter.AbstractEssStreetscooter.ChannelId;

public class PowerHandler implements BiConsumer<Integer, Integer> {

	private static final int MINUTES_TO_WAIT_FOR_2ND_TRY = 30;
	private OpenemsComponent parent;
	private Logger log;
	private LocalDateTime lastRestartAfterFault;
	private int attempsToRestart;

	public PowerHandler(OpenemsComponent parent, Logger log) {
		this.parent = parent;
		this.log = log;
	}

	@Override
	public void accept(Integer activePower, Integer reactivePower) {

		checkForResettingFault();

		if (isInverterInFaultMode()) {
			doErrorHandling();
		}

		if (isErrorHandling()) {
			return;
		}

		// System is in normal mode
		if (!isRunning()) {
			setRunning();
		}
		if (isRunning() && !isEnabled()) {
			setEnabled();
		}
		if (isRunning() && isEnabled() && isInverterInNormalMode()) {
			writeActivePower(activePower);
		}
	}

	private boolean isErrorHandling() {
		return lastRestartAfterFault != null;
	}

	private void checkForResettingFault() {
		if (isInverterInNormalMode() && isWaitingPeriodAfterFaultRestartPassed()) {
			lastRestartAfterFault = null;
			attempsToRestart = 0;
		}
	}

	private void doErrorHandling() {
		if (lastRestartAfterFault == null && attempsToRestart == 0) {
			lastRestartAfterFault = LocalDateTime.now();
			attempsToRestart = 1;
			setRunning();
			setEnabled();
			log.info("Try to restart the system for the first time after detecting fault");
		} else {
			if (isWaitingPeriodAfterFaultRestartPassed() && attempsToRestart == 1) {
				attempsToRestart++;
				setRunning();
				setEnabled();
				log.info("Try to restart the system for the second time after detecting fault");
			} else if (isWaitingPeriodAfterFaultRestartPassed() && attempsToRestart > 1) {
				// Do nothing, let system in fault mode
				log.error("System could not be restarted!");
				// TODO set an error in a state channel 
			}
		}
	}

	private void writeActivePower(Integer activePower) {
		try {
			IntegerWriteChannel setActivePowerChannel = parent.channel(ChannelId.INVERTER_SET_ACTIVE_POWER);
			setActivePowerChannel.setNextWriteValue(activePower);
		} catch (OpenemsException e) {
			log.error("Unable to set ActivePower: " + e.getMessage());
		}
	}

	private boolean isInverterInNormalMode() {
		IntegerReadChannel inverterModeChannel = parent.channel(ChannelId.INVERTER_MODE);
		return inverterModeChannel.value().get().equals(ChannelId.INVERTER_MODE_NORMAL);
	}

	private boolean isInverterInFaultMode() {
		IntegerReadChannel inverterModeChannel = parent.channel(ChannelId.INVERTER_MODE);
		return inverterModeChannel.value().get().equals(ChannelId.INVERTER_MODE_FAULT);
	}

	private void setEnabled() {
		try {
			BooleanWriteChannel channel = parent.channel(ChannelId.ICU_ENABLED);
			channel.setNextWriteValue(true);
		} catch (Exception e) {
			log.error("Unable to set icu enabled: " + e.getMessage());
		}
	}

	private void setRunning() {
		try {
			BooleanWriteChannel channel = parent.channel(ChannelId.ICU_RUN);
			channel.setNextWriteValue(true);
		} catch (Exception e) {
			log.error("Unable to set icu run: " + e.getMessage());
		}
	}

	private boolean isEnabled() {
		BooleanReadChannel icuEnabled = parent.channel(ChannelId.ICU_ENABLED);
		boolean value = icuEnabled.value().orElse(false);
		return value;
	}

	private boolean isRunning() {
		BooleanReadChannel icuRunChannel = parent.channel(ChannelId.ICU_RUN);
		boolean value = icuRunChannel.value().orElse(false);
		return value;
	}

	private boolean isWaitingPeriodAfterFaultRestartPassed() {
		return lastRestartAfterFault != null
				&& lastRestartAfterFault.plusMinutes(MINUTES_TO_WAIT_FOR_2ND_TRY).isAfter(LocalDateTime.now());
	}
}
