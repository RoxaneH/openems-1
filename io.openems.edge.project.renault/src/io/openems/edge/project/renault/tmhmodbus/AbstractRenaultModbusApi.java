package io.openems.edge.project.renault.tmhmodbus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.OpenemsConstants;
import io.openems.common.channel.Level;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.ChannelAddress;
import io.openems.common.worker.AbstractWorker;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.WriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusRecord;
import io.openems.edge.common.modbusslave.ModbusRecordChannel;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.controller.api.common.ApiWorker;
import io.openems.edge.controller.api.common.WritePojo;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.refu88k.EssREFUstore88K;
import io.openems.edge.project.renault.battery.renaultzoe.BatteryRenaultZoe;

public abstract class AbstractRenaultModbusApi extends AbstractOpenemsComponent
		implements Controller, OpenemsComponent {

	public static final int UNIT_ID = 1;

	private final Logger log = LoggerFactory.getLogger(AbstractRenaultModbusApi.class);

	private final ApiWorker apiWorker = new ApiWorker();
	private final MyProcessImage processImage;

	/**
	 * Holds the link between Modbus address and ModbusRecord.
	 */
	protected final TreeMap<Integer, ModbusRecord> records = new TreeMap<>();

	protected abstract ComponentManager getComponentManager();

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		UNABLE_TO_START(Doc.of(Level.FAULT) //
				.text("Unable to start Modbus-Api Server"));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	public AbstractRenaultModbusApi() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ChannelId.values() //
		);

		this.processImage = new MyProcessImage(this);
	}

	protected void activate(ComponentContext context, String id, String alias, boolean enabled, int apiTimeout,
			int technicalUnitId, String[] batteryIds, String[] inverterIds)
			throws ModbusException, OpenemsNamedException {
		super.activate(context, id, alias, enabled);

		this.apiWorker.setTimeoutSeconds(apiTimeout);

		if (!this.isEnabled()) {
			// abort if disabled
			return;
		}

		List<BatteryRenaultZoe> batteries = new ArrayList<BatteryRenaultZoe>();
		for (String batteryId : batteryIds) {
			Battery b = getComponentManager().getComponent(batteryId);
			if (b instanceof BatteryRenaultZoe) {
				batteries.add((BatteryRenaultZoe) b);
			}
		}

		List<EssREFUstore88K> inverters = new ArrayList<EssREFUstore88K>();
		for (String inverterId : inverterIds) {
			SymmetricEss i = getComponentManager().getComponent(inverterId);
			if (i instanceof EssREFUstore88K) {
				inverters.add((EssREFUstore88K) i);
			}
		}

		// Initialize Modbus Records
		this.initalizeModbusRecords(MyModbusSlaveDefinition.getModbusSlaveDefinition(this.getComponentManager(),
				this.getComponentManager().getComponent(OpenemsConstants.SUM_ID), batteries, inverters,
				technicalUnitId));
		// Start Modbus-Server
		this.startApiWorker.activate(id);
	}

	protected void deactivate() {
		this.startApiWorker.deactivate();
		ModbusSlaveFactory.close();
		super.deactivate();
	}

	protected abstract ModbusSlave createSlave() throws ModbusException;

	private final AbstractWorker startApiWorker = new AbstractWorker() {

		private static final int DEFAULT_WAIT_TIME = 5000; // 5 seconds

		private final Logger log = LoggerFactory.getLogger(AbstractWorker.class);

		private ModbusSlave slave = null;

		@Override
		protected void forever() {
			if (this.slave == null) {
				try {
					// start new server
					this.slave = AbstractRenaultModbusApi.this.createSlave();
					slave.addProcessImage(UNIT_ID, AbstractRenaultModbusApi.this.processImage);
					slave.open();

					AbstractRenaultModbusApi.this.logInfo(this.log,
							"Modbus-Api started with UnitId [" + AbstractRenaultModbusApi.UNIT_ID + "].");
					AbstractRenaultModbusApi.this.channel(ChannelId.UNABLE_TO_START).setNextValue(false);
				} catch (ModbusException e) {
					ModbusSlaveFactory.close();
					AbstractRenaultModbusApi.this.logError(this.log, "Unable to start Modbus-Api: " + e.getMessage());
					AbstractRenaultModbusApi.this.channel(ChannelId.UNABLE_TO_START).setNextValue(true);
				}

			} else {
				// regular check for errors
				String error = slave.getError();
				if (error != null) {
					AbstractRenaultModbusApi.this.logError(this.log, "Unable to start Modbus-Api: " + error);
					AbstractRenaultModbusApi.this.channel(ChannelId.UNABLE_TO_START).setNextValue(true);
					this.slave = null;
					// stop server
					ModbusSlaveFactory.close();
				}
			}
		}

		@Override
		protected int getCycleTime() {
			return DEFAULT_WAIT_TIME;
		}

	};

	/**
	 * Prepares the Modbus Slave Definition for usage by {@link MyProcessImage}.
	 * 
	 * @param modbusSlaveDefinition the modbus slave definition
	 */
	private void initalizeModbusRecords(TreeMap<Integer, ModbusRecord> records) {
		for (ModbusRecord record : records.values()) {
			// Handle writes to the Channel; limited to ModbusRecordChannels
			if (record instanceof ModbusRecordChannel) {
				ModbusRecordChannel r = (ModbusRecordChannel) record;
				r.onWriteValue((value) -> {
					ChannelAddress channelAddress = new ChannelAddress(r.getComponentId(), r.getChannelId().id());
					Channel<?> readChannel;
					try {
						readChannel = this.getComponentManager().getChannel(channelAddress);
					} catch (IllegalArgumentException | OpenemsNamedException e) {
						this.logWarn(this.log, "Unable to get Channel [" + channelAddress + "]");
						return;
					}
					if (!(readChannel instanceof WriteChannel)) {
						this.logWarn(this.log, "Unable to write to Read-Only-Channel [" + readChannel.address() + "]");
						return;
					}
					WriteChannel<?> channel = (WriteChannel<?>) readChannel;
					this.apiWorker.addValue(channel, new WritePojo(value));
				});
			}
		}
		this.records.clear();
		this.records.putAll(records);
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.apiWorker.run();
	}

	@Override
	protected void logDebug(Logger log, String message) {
		super.logDebug(log, message);
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	protected void logWarn(Logger log, String message) {
		super.logWarn(log, message);
	}
}
