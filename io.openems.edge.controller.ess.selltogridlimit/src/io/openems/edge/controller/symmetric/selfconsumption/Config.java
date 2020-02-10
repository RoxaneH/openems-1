package io.openems.edge.controller.symmetric.selfconsumption;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition( //
		name = "Controller Ess Sell-to-Grid Limit", //
		description = "Charges the Ess to limit the Sell-to-Grid power")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlEssSellToGridLimit0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Ess-ID", description = "ID of Ess device.")
	String ess_id() default "ess0";

	@AttributeDefinition(name = "Grid-Meter-Id", description = "ID of the Grid-Meter.")
	String meter_id() default "meter0";
	
	@AttributeDefinition(name = "Maximum allowed Sell-To-Grid power", description = "The target limit for sell-to-grid power.")
	int maximumSellToGridPower() default 5_000;

	String webconsole_configurationFactory_nameHint() default "Controller Ess Sell-to-Grid Limit [{id}]";
}