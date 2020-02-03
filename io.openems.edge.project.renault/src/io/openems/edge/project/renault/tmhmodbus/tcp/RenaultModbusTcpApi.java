package io.openems.edge.project.renault.tmhmodbus.tcp;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.project.renault.tmhmodbus.AbstractRenaultModbusApi;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Project.Renault.Controller.Api.ModbusTcp", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
public class RenaultModbusTcpApi extends AbstractRenaultModbusApi implements Controller, OpenemsComponent {

	public static final int DEFAULT_PORT = 502;
	public static final int DEFAULT_MAX_CONCURRENT_CONNECTIONS = 5;

	@Reference
	protected ComponentManager componentManager;

	private Config config = null;

	public RenaultModbusTcpApi() {
		super();
	}

	@Activate
	void activate(ComponentContext context, Config config) throws ModbusException, OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.apiTimeout(),
				config.technicalUnitId(), config.batteryIds(), config.inverterIds());
		this.config = config;
	}
	
	
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ComponentManager getComponentManager() {
		return this.componentManager;
	}

	@Override
	protected ModbusSlave createSlave() throws ModbusException {
		return ModbusSlaveFactory.createTCPSlave(this.config.port(), this.config.maxConcurrentConnections());
	}

}
