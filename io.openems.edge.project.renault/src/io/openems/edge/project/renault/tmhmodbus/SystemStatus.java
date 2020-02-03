package io.openems.edge.project.renault.tmhmodbus;

import io.openems.common.types.OptionsEnum;

public enum SystemStatus implements OptionsEnum {
	UNDEFINED("Undefined", -1), //
	OFF("Off", 0), //
	STANDBY("Standby", 50), //
	STARTUP("Startup", 100), //
	ON("On", 150), //
	FAULT("Fault", 200), //	
	SLEEP("Sleep", 300), //
	;

	private SystemStatus(String name, int value) {
		this.name = name;
		this.value = value;
	}

	private int value;
	private String name;

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}

}
