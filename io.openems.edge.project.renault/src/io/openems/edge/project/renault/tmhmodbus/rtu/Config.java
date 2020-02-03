package io.openems.edge.project.renault.tmhmodbus.rtu;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition( //
		name = "Project Renault Controller TMH Modbus/RTU Slave", //
		description = "Implements the TMH Modbus Slave Controller.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlRenaultApiModbusRtu0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Port-Name", description = "The name of the serial port - e.g. '/dev/ttyUSB0' or 'COM3'")
	String portName() default "/dev/ttyUSB0";

	@AttributeDefinition(name = "Baudrate", description = "The baudrate - e.g. 9600, 19200, 38400, 57600 or 115200")
	int baudRate() default 9600;

	@AttributeDefinition(name = "Databits", description = "The number of databits - e.g. 8")
	int databits() default 8;

	@AttributeDefinition(name = "Stopbits", description = "The number of stopbits - '1', '1.5' or '2'")
	Stopbit stopbits() default Stopbit.ONE;

	@AttributeDefinition(name = "Parity", description = "The parity - 'none', 'even', 'odd', 'mark' or 'space'")
	Parity parity() default Parity.NONE;

	@AttributeDefinition(name = "Technical Unit ID", description = "Sets the technical Unit-ID.")
	int technicalUnitId() default 1;
		
	@AttributeDefinition(name = "Battery-ID", description = "ID of Battery.")
	String[] batteryIds() default {};
	
	@AttributeDefinition(name = "Inverter-ID", description = "ID of Inverter.")
	String[] inverterIds() default {};
		
	@AttributeDefinition(name = "Api-Timeout", description = "Sets the timeout in seconds for updates on Channels set by this Api.")
	int apiTimeout() default 60;

	String webconsole_configurationFactory_nameHint() default "Project Renault Controller TMH Modbus/RTU Slave [{id}]";
}