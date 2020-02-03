package io.openems.edge.project.renault.tmhmodbus.tcp;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition( //
		name = "Project Renault Controller TMH Modbus/TCP Slave", //
		description = "Implements the TMH Modbus Slave Controller.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlRenaultApiModbusTcp0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Port", description = "Port on which the server should listen.")
	int port() default RenaultModbusTcpApi.DEFAULT_PORT;

	@AttributeDefinition(name = "Technical Unit ID", description = "Sets the technical Unit-ID.")
	int technicalUnitId() default 1;
	
	@AttributeDefinition(name = "Battery-ID", description = "ID of Battery.")
	String[] batteryIds() default {};
	
	@AttributeDefinition(name = "Inverter-ID", description = "ID of Inverter.")
	String[] inverterIds() default {};

	@AttributeDefinition(name = "Api-Timeout", description = "Sets the timeout in seconds for updates on Channels set by this Api.")
	int apiTimeout() default 60;

	@AttributeDefinition(name = "Max concurrent connections", description = "Sets the maximum number of concurrent connections via Modbus.")
	int maxConcurrentConnections() default RenaultModbusTcpApi.DEFAULT_MAX_CONCURRENT_CONNECTIONS;

	String webconsole_configurationFactory_nameHint() default "Project Renault Controller TMH Modbus/TCP Slave [{id}]";
}