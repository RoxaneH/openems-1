package io.openems.edge.project.renault.battery.renaultzoe;

import java.time.LocalDateTime;
import java.util.Optional;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.ModbusException;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.SymmetricEss.ChannelId;

@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Bms.RenaultZoe", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
)
public class BatteryRenaultZoe extends AbstractOpenemsModbusComponent
		implements Battery, OpenemsComponent, EventHandler, ModbusSlave {

	@Reference
	protected ConfigurationAdmin cm;

	private final Logger log = LoggerFactory.getLogger(BatteryRenaultZoe.class);
	private String modbusBridgeId;
	private State state = State.UNDEFINED;

	// if configuring is needed this is used to go through the necessary steps
	private Config config;
	private int unsuccessfulStarts = 0;
	private LocalDateTime errorDelayIsOver = null;
	private LocalDateTime startAttemptTime = null;
	private LocalDateTime pendingTimestamp;

	public BatteryRenaultZoe() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Battery.ChannelId.values(), //
				RenaultZoeChannelId.values() //
		);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		/**
		 * Adds dynamically created channels and save them into a map to access them if modbus tasks are created
		 */
		
		this.config = config;
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id());
		this.modbusBridgeId = config.modbus_id();
		this.channel(RenaultZoeChannelId.BATTERY_ID).setNextValue(config.batteryId());
		this.channel(Battery.ChannelId.CAPACITY).setNextValue(config.capacity());
		this.doChannelMapping();
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void handleStateMachine() {
		log.info("BatteryRenaultZoe.handleStateMachine(): State: " + this.getStateMachineState());
		boolean readyForWorking = false;
		IntegerWriteChannel stChannel = this.channel(RenaultZoeChannelId.START_STOP);
		try {
			stChannel.setNextWriteValue(this.config.StartStop());
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		switch (this.getStateMachineState()) {
		case ERROR:
//			this.stopSystem();
			errorDelayIsOver = LocalDateTime.now().plusSeconds(config.errorDelay());
			this.setStateMachineState(State.ERRORDELAY);
			break;
		case ERRORDELAY:
			if (LocalDateTime.now().isAfter(errorDelayIsOver)) {
				errorDelayIsOver = null;
				if (this.isError()) {
					this.setStateMachineState(State.ERROR);
				} else {
					this.setStateMachineState(State.OFF);
				}
			}
			break;
		case INIT:			
			if (this.isSystemRunning()) {
				this.setStateMachineState(State.RUNNING);
				unsuccessfulStarts = 0;
				startAttemptTime = null;
			} else {
				if (startAttemptTime.plusSeconds(config.maxStartTime()).isBefore(LocalDateTime.now())) {
					startAttemptTime = null;
					unsuccessfulStarts++;
					this.stopSystem();
					this.setStateMachineState(State.STOPPING);
					if (unsuccessfulStarts >= config.maxStartAttempts()) {
						errorDelayIsOver = LocalDateTime.now().plusSeconds(config.startUnsuccessfulDelay());
						this.setStateMachineState(State.ERRORDELAY);
						unsuccessfulStarts = 0;
					}
				}
			}
			break;
		case OFF:
			log.debug("in case 'OFF'; try to start the system");
			log.debug("set state to 'INIT'");
			this.setStateMachineState(State.INIT);
			startAttemptTime = LocalDateTime.now();
			break;
		case RUNNING:
			if (this.isError()) {
				this.setStateMachineState(State.ERROR);
			} else if (!this.isSystemRunning()) {
				this.setStateMachineState(State.UNDEFINED);
			} else {
				this.setStateMachineState(State.RUNNING);
				readyForWorking = true;
			}
			break;
		case STOPPING:
			if (this.isError()) {
				this.setStateMachineState(State.ERROR);
			} else {
				if (this.isSystemStopped()) {
					this.setStateMachineState(State.OFF);
				}
			}
			break;
		case UNDEFINED:
			if (this.isError()) {
				this.setStateMachineState(State.ERROR);
			} else if (this.isSystemStopped()) {
				this.setStateMachineState(State.OFF);
			} else if (this.isSystemRunning()) {
				this.setStateMachineState(State.RUNNING);
			} else if (this.isSystemStatePending()) {
				this.setStateMachineState(State.PENDING);
			}
			break;
		case PENDING:
			if (this.pendingTimestamp == null) {
				this.pendingTimestamp = LocalDateTime.now();
			}
			if (this.pendingTimestamp.plusSeconds(this.config.pendingTolerance()).isBefore(LocalDateTime.now())) {
				// System state could not be determined, stop and start it
				this.pendingTimestamp = null;
//				this.stopSystem();
				this.setStateMachineState(State.OFF);
			} else {
				if (this.isError()) {
					this.setStateMachineState(State.ERROR);
					this.pendingTimestamp = null;
				} else if (this.isSystemStopped()) {
					this.setStateMachineState(State.OFF);
					this.pendingTimestamp = null;
				} else if (this.isSystemRunning()) {
					this.setStateMachineState(State.RUNNING);
					this.pendingTimestamp = null;
				}
			}
			break;
		case STANDBY:
			break;
		}

		this.getReadyForWorking().setNextValue(readyForWorking);
	}



	public State getStateMachineState() {
		return state;
	}

	public void setStateMachineState(State state) {
		this.state = state;
		this.channel(RenaultZoeChannelId.HV_BAT_STATE).setNextValue(this.state);
	}

	public String getModbusBridgeId() {
		return modbusBridgeId;
	}

	public Channel<Integer> getBatteryId() {
		return this.channel(RenaultZoeChannelId.BATTERY_ID);
	}
	
	public Channel<Integer> getAvailablePowerDischarge() {
		return this.channel(RenaultZoeChannelId.AVAILABLE_POWER);
	}
	
	public Channel<Integer> getAvailablePowerCharge() {
		return this.channel(RenaultZoeChannelId.CHARGING_POWER);
	}
	
	public Channel<Integer> getAvailableEnergyDischarge() {
		return this.channel(RenaultZoeChannelId.AVAILABLE_ENERGY);
	}
	
	public Channel<Integer> getAvailableEnergyCharge() {
		try {
			int availableEnergyCharge = (getCapacity().value().get() - getAvailableEnergyDischarge().value().get());
			this.channel(RenaultZoeChannelId.AVAILABLE_ENERGY_CHARGE).setNextValue(availableEnergyCharge);
		} catch (Exception e) {
			// TODO: handle exception
		}
		return this.channel(RenaultZoeChannelId.AVAILABLE_ENERGY_CHARGE);
	}
		
	private boolean isStartAllowed() {
		// TODO Auto-generated method stub
		boolean startIsAllowed = false;
		EnumReadChannel hvPowerConnectionChannel = this.channel(RenaultZoeChannelId.HV_POWER_CONNECTION);
		HvPowerConnection hvPowerConnection = hvPowerConnectionChannel.value().asEnum();
		
		switch (hvPowerConnection) {
		case UNDEFINED:
			break;
		case NOT_USED:
			break;
		case CLOSING_OF_POWER_CONTACTS_ALLOWED:
			startIsAllowed = true;
			break;
		case CLOSING_OF_POWER_CONTACTS_NOT_ALLOWED:
			startIsAllowed = true;
			break;
		case UNAVAILABLE_VALUE:
			break;
		}
		return startIsAllowed;
	}
	
	
	private boolean isError() {
		if (this.isFailure2()) {
			return true;
		}
		return false;
	}
	
	private boolean isWarning() {
		if (this.isFailure1()) {
			return true;
		}
		return false;
	}
	

	private boolean isFailure1() {
		EnumReadChannel hvBatLevel1FailureChannel = this.channel(RenaultZoeChannelId.HV_BAT_LEVEL1_FAILURE);
		HvBatLevel1Failure hvBatLevel1Failure = hvBatLevel1FailureChannel.value().asEnum();
		return hvBatLevel1Failure == HvBatLevel1Failure.CAUTION_LEVEL_1_DEFAULT;
	}

	private boolean isFailure2() {
		EnumReadChannel hvBatLevel2FailureChannel = this.channel(RenaultZoeChannelId.HV_BAT_LEVEL2_FAILURE);
		HvBatLevel2Failure hvBatLevel2Failure = hvBatLevel2FailureChannel.value().asEnum();
		return hvBatLevel2Failure == HvBatLevel2Failure.FAILURE_LEVEL_2_DEFAULT;
	}
	
	

	private boolean isSystemRunning() {
		return true;
//		EnumReadChannel powerRelayStateChannel = this.channel(RenaultZoeChannelId.POWER_RELAY_STATE);
//		PowerRelayState powerRelayState = powerRelayStateChannel.value().asEnum();
//		return powerRelayState == PowerRelayState.CLOSED;
	}

	private boolean isSystemStopped() {
		return true;
		
//		EnumReadChannel powerRelayStateChannel = this.channel(RenaultZoeChannelId.POWER_RELAY_STATE);
//		PowerRelayState powerRelayState = powerRelayStateChannel.value().asEnum();
//		return powerRelayState == PowerRelayState.OPENED;
	}

	private void startSystem() {
		this.log.debug("Try to start system");
		this.isStartAllowed();
		if (this.isStartAllowed()) {
			this.sendStartCommand();
		} else {
			this.stopSystem();
		}
	
	}

	private void sendStartCommand() {
		IntegerWriteChannel stChannel = this.channel(RenaultZoeChannelId.START_STOP);
		try {
			stChannel.setNextWriteValue(this.config.StartStop());
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private boolean isEndOfCharge() {
		EnumReadChannel endOfChargeRequestChannel = this.channel(RenaultZoeChannelId.END_OF_CHARGE_REQUEST);
		EndOfChargeRequest endOfChargeRequest = endOfChargeRequestChannel.value().asEnum();
		return endOfChargeRequest == EndOfChargeRequest.END_OF_CHARGE_REQUEST;
	}
	
	
	/**
	 * Checks whether system has an undefined state
	 */
	private boolean isSystemStatePending() {
		return !isSystemRunning() && !isSystemStopped();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			handleBatteryState();
			break;
		}
	}

	private void handleBatteryState() {
		switch (config.batteryState()) {
		case DEFAULT:
			this.handleStateMachine();
			break;
		case OFF:
			this.stopSystem();
			break;
		case ON:
			this.startSystem();
			break;
		}
	}

	private void stopSystem() {
		this.log.debug("Stop system");
		EnumWriteChannel startStopChannel = this.channel(RenaultZoeChannelId.START_STOP);
		try {
			startStopChannel.setNextWriteValue(StartStop.STOP);
		} catch (OpenemsNamedException e) {
			log.error("Problem occurred during send start command");
		}
	}

	/**
	 * writes current channel values to corresponding values of the channels given
	 * from battery interface
	 */
	private void doChannelMapping() {

		/**
		 * Writes the calculated values to corresponding values of the battery interface
		 */
		
		this.channel(RenaultZoeChannelId.HV_BAT_INSTANT_CURRENTS).onChange((oldValue, newValue) -> {
			@SuppressWarnings("unchecked")
			Optional<Float> curOpt = (Optional<Float>) newValue.asOptional();
			if (!curOpt.isPresent()) {
				return;
			}
			
			this.channel(RenaultZoeChannelId.HV_BAT_INSTANT_CURRENT).setNextValue((float)((-1)*((curOpt.get()*0.25) - 500)));
			this.channel(Battery.ChannelId.CURRENT).setNextValue(((-1000)*((curOpt.get()*0.25) - 500)));
			
			
		});

		this.channel(RenaultZoeChannelId.HV_NETWORK_VOLTAGE).onChange((oldValue, newValue) -> {
			@SuppressWarnings("unchecked")
			Optional<Integer> vOpt = (Optional<Integer>) newValue.asOptional();
			if (!vOpt.isPresent()) {
				return;
			}
			this.channel(Battery.ChannelId.VOLTAGE).setNextValue((int) (vOpt.get()));
		});

		this.channel(RenaultZoeChannelId.AVAILABLE_POWER).onChange((oldValue, newValue) -> {
			@SuppressWarnings("unchecked")
			Optional<Integer> disPowerOpt = (Optional<Integer>) newValue.asOptional();
			if (!disPowerOpt.isPresent()) {
				return;
			}
			int voltage = (int) this.channel(RenaultZoeChannelId.HV_NETWORK_VOLTAGE).value().get();
			this.channel(Battery.ChannelId.DISCHARGE_MAX_CURRENT).setNextValue(((int) (disPowerOpt.get()) / voltage));
		});

		this.channel(RenaultZoeChannelId.CHARGING_POWER).onChange((oldValue, newValue) -> {
			@SuppressWarnings("unchecked")
			Optional<Integer> chaPowerOpt = (Optional<Integer>) newValue.asOptional();
			if (!chaPowerOpt.isPresent()) {
				return;
			}
			int voltage = (int) this.channel(RenaultZoeChannelId.HV_NETWORK_VOLTAGE).value().get();
			this.channel(Battery.ChannelId.CHARGE_MAX_CURRENT).setNextValue(((int) (chaPowerOpt.get()) / voltage));
		});
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //

				/*
				 * Battery String1
				 */
				new FC3ReadRegistersTask(0x64, Priority.HIGH, //
						m(RenaultZoeChannelId.USER_SOC, new UnsignedWordElement(0x64)), //
						m(RenaultZoeChannelId.AVAILABLE_ENERGY, new UnsignedWordElement(0x65)), //
						m(RenaultZoeChannelId.AVAILABLE_POWER, new UnsignedWordElement(0x66),
								ElementToChannelConverter.SCALE_FACTOR_3), //
						m(RenaultZoeChannelId.CHARGING_POWER, new UnsignedWordElement(0x67), //
								ElementToChannelConverter.SCALE_FACTOR_3), //
						m(RenaultZoeChannelId.CELL_HIGHEST_VOLTAGE, new UnsignedWordElement(0x68),
								ElementToChannelConverter.SCALE_FACTOR_3), ////
						m(RenaultZoeChannelId.CELL_LOWEST_VOLTAGE, new UnsignedWordElement(0x69),
								ElementToChannelConverter.SCALE_FACTOR_3), ////
						m(RenaultZoeChannelId.HV_BAT_INSTANT_CURRENTS, new SignedWordElement(0x6A)),
						m(RenaultZoeChannelId.HV_NETWORK_VOLTAGE, new UnsignedWordElement(0x6B)), //
						m(RenaultZoeChannelId.HV_BATTERY_MAX_TEMP, new UnsignedWordElement(0x6C)), //
						m(RenaultZoeChannelId.HV_BAT_STATE, new UnsignedWordElement(0x6D)), //
						m(RenaultZoeChannelId.HV_BAT_HEALTH, new UnsignedWordElement(0x6E)), //
						m(RenaultZoeChannelId.HV_BATTERY_TEMP, new UnsignedWordElement(0x6F)), //
						m(RenaultZoeChannelId.HV_ISOLATON_IMPEDANCE, new UnsignedWordElement(0x70)), //
						m(RenaultZoeChannelId.LBCPRUN_ANSWER, new UnsignedWordElement(0x71)), //
						m(RenaultZoeChannelId.HV_POWER_CONNECTION, new UnsignedWordElement(0x72)), //
						m(RenaultZoeChannelId.HV_BAT_LEVEL1_FAILURE, new UnsignedWordElement(0x73)), //
						m(RenaultZoeChannelId.HV_BAT_LEVEL2_FAILURE, new UnsignedWordElement(0x74)), //
						m(RenaultZoeChannelId.HV_BAT_SERIAL_NUMBER, new UnsignedWordElement(0x75)), //
						m(RenaultZoeChannelId.LBC2_REFUSE_TO_SLEEP, new UnsignedWordElement(0x76)), //
						m(RenaultZoeChannelId.ELEC_MASCHINE_SPEED, new UnsignedWordElement(0x77)), //
						m(RenaultZoeChannelId.ETS_SLEEP_MODE, new UnsignedWordElement(0x78)), //
						m(RenaultZoeChannelId.SCH_WAKE_UP_SLEEP_COMMAND, new UnsignedWordElement(0x79)), //
						m(RenaultZoeChannelId.WAKE_UP_TYPE, new UnsignedWordElement(0x7A)), //
						m(RenaultZoeChannelId.LBCPRUN_KEY, new UnsignedWordElement(0x7B)), //
						m(RenaultZoeChannelId.OPERATING_TYPE, new UnsignedWordElement(0x7C)), //
						m(RenaultZoeChannelId.POWER_RELAY_STATE, new UnsignedWordElement(0x7D)), //
						m(RenaultZoeChannelId.DISTANCE_TOTALIZER_COPY, new UnsignedWordElement(0x7E)), //
						m(RenaultZoeChannelId.ABSOLUTE_TIME_SINCE_1RST_IGNITION, new UnsignedWordElement(0x7F)), //
						m(RenaultZoeChannelId.CELL_LOWEST_VOLTAGE_RCY, new UnsignedWordElement(0x80)), //
						m(RenaultZoeChannelId.CELL_HIGHEST_VOLTAGE_RCY, new UnsignedWordElement(0x81)), //
						m(RenaultZoeChannelId.LBCRUN_ANSWER_RCY, new UnsignedWordElement(0x82)), //
						m(RenaultZoeChannelId.HV_BATTERY_MAX_TEMP_RCY, new UnsignedWordElement(0x83)), //
						m(RenaultZoeChannelId.HV_POWER_CONNECTION_RCY, new UnsignedWordElement(0x84)), //
						m(RenaultZoeChannelId.HV_BAT_LEVEL2_FAILURE_RCY, new UnsignedWordElement(0x85)), //
						m(RenaultZoeChannelId.SAFETY_MODE_1_FLAG_RCY, new UnsignedWordElement(0x86)), //
						m(RenaultZoeChannelId.LBCPRUN_KEY_RCY, new UnsignedWordElement(0x87)), //
						m(RenaultZoeChannelId.VEHICLE_ID, new UnsignedWordElement(0x88)), //
						m(RenaultZoeChannelId.END_OF_CHARGE_REQUEST, new UnsignedWordElement(0x89)), //
						m(RenaultZoeChannelId.LBC_REFUSE_TO_SLEEP, new UnsignedWordElement(0x8A)), //
						m(RenaultZoeChannelId.ISOL_DIAG_AUTHORISATION, new UnsignedWordElement(0x8B)), //
						m(RenaultZoeChannelId.SAFETY_MODE_1_FLAG, new UnsignedWordElement(0x8C))), //

				new FC16WriteRegistersTask(0x8D, //
						m(RenaultZoeChannelId.START_STOP, new UnsignedDoublewordElement(0x8D)) //

				)

//						new DummyRegisterElement(0x8D, 0x15F),
//						m(RenaultZoeChannelId.STR_ST, new UnsignedWordElement(0x160), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)
		)//
//
//				new FC6WriteRegisterTask(0x161, //
//						m(RenaultZoeChannelId.EN_STRING, new UnsignedWordElement(0x161)) //
//				), new FC6WriteRegisterTask(0x162, //
//						m(RenaultZoeChannelId.CON_STRING, new UnsignedWordElement(0x162)) //
//				)

		/*
		 * Battery Stack
		 */

//				new FC3ReadRegistersTask(0x500, Priority.HIGH, //
//						m(RenaultZoeChannelId.SERIAL_NO, new UnsignedWordElement(0x500), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.MOD_V, new UnsignedWordElement(0x501), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.TYP, new UnsignedWordElement(0x502), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.BAT_MAN, new UnsignedWordElement(0x503), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
//						m(RenaultZoeChannelId.BAT_MODEL, new UnsignedWordElement(0x504), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STATE2, new UnsignedWordElement(0x505), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.WARR_DT, new UnsignedWordElement(0x506), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.INST_DT, new UnsignedWordElement(0x507), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.AH_RTG, new UnsignedWordElement(0x508), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.WH_RTG, new UnsignedWordElement(0x509), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.W_CHA_RTE_MAX, new UnsignedWordElement(0x510), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.W_DIS_CHA_RTE_MAX, new UnsignedWordElement(0x511), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.V_MAX, new UnsignedWordElement(0x512), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.V_MIN, new UnsignedWordElement(0x513), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.CELL_V_MAX, new UnsignedWordElement(0x514), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.CELL_V_MAX_STR, new UnsignedWordElement(0x515), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.CELL_V_MIN, new UnsignedWordElement(0x516), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.CELL_V_MIN_STR, new UnsignedWordElement(0x517), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.CHARGE_WH, new UnsignedWordElement(0x518), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.DISCHARGE_WH, new UnsignedWordElement(0x519), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.TOTAL_CAP, new UnsignedWordElement(0x520), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.REST_CAP, new UnsignedWordElement(0x521), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.A, new UnsignedWordElement(0x522), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.V, new UnsignedWordElement(0x523), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.A_CHA_MAX, new UnsignedWordElement(0x524), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.A_DIS_CHA_MAX, new UnsignedWordElement(0x525), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.W, new UnsignedWordElement(0x526), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.REQ_INV_STATE, new UnsignedWordElement(0x527), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)), //

//				new FC6WriteRegisterTask(0x528, //
//						m(RenaultZoeChannelId.REQ_W, new UnsignedWordElement(0x528)) //
//				), new FC6WriteRegisterTask(0x529, //
//						m(RenaultZoeChannelId.REQ_MODE, new UnsignedWordElement(0x529)) //
//				), new FC3ReadRegistersTask(0x530, Priority.HIGH, //
//						m(RenaultZoeChannelId.CTL_MODE, new UnsignedWordElement(0x530), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)), //
//				new FC6WriteRegisterTask(0x531, //
//						m(RenaultZoeChannelId.ON_OFF, new UnsignedWordElement(0x531)) //
//				), new FC3ReadRegistersTask(0x532, Priority.HIGH, //
//						m(RenaultZoeChannelId.N_STR, new UnsignedWordElement(0x532), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.N_STR_CON, new UnsignedWordElement(0x533), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_V_MAX, new UnsignedWordElement(0x534), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_V_MAX_STR, new UnsignedWordElement(0x535), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_V_MIN, new UnsignedWordElement(0x536), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_V_MIN_STR, new UnsignedWordElement(0x537), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_V_AVG, new UnsignedWordElement(0x538), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_A_MAX, new UnsignedWordElement(0x539), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_A_MAX_STR, new UnsignedWordElement(0x540), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_A_MIN, new UnsignedWordElement(0x541), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_A_MIN_STR, new UnsignedWordElement(0x542), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.STR_A_AVG, new UnsignedWordElement(0x543), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.ALARM_STACK, new UnsignedWordElement(0x544), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1), //
//						m(RenaultZoeChannelId.FAULT_STACK, new UnsignedWordElement(0x545), //
//								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)) //
		;//
//
	}

	@Override
	public String debugLog() {
		return "SoC: " + this.channel(RenaultZoeChannelId.USER_SOC).value() //
				+ " | Available Energy: " + this.channel(RenaultZoeChannelId.AVAILABLE_ENERGY).value() //
				+ " | Available (Discharging) Power: " + this.channel(RenaultZoeChannelId.AVAILABLE_POWER).value() //
				+ " | Max Discharging Current: " + this.getDischargeMaxCurrent().value()
				+ " | Available (Charging) Power: " + this.channel(RenaultZoeChannelId.CHARGING_POWER).value() //
				+ " | Max Charging Current: " + this.getChargeMaxCurrent().value() + " | Cell Highest Voltage: "
				+ this.channel(RenaultZoeChannelId.CELL_HIGHEST_VOLTAGE).value() //
				+ " | Cell Lowest Voltage: " + this.channel(RenaultZoeChannelId.CELL_LOWEST_VOLTAGE).value() //
				+ " | HV Instant Cur: " + this.channel(RenaultZoeChannelId.HV_BAT_INSTANT_CURRENT).value()
				+ " | Current: " + this.channel(Battery.ChannelId.CURRENT).value()
				+ " | Voltage: " + this.getVoltage().value()
				+ "| State: " + this.getStateMachineState() //
				+ "| Max Cell Voltage: " + this.channel(RenaultZoeChannelId.CELL_HIGHEST_VOLTAGE).value() //
				+ "| Min Cell Voltage: " + this.channel(RenaultZoeChannelId.CELL_LOWEST_VOLTAGE).value() //
				+ "| Max Bat Temp: " + this.channel(RenaultZoeChannelId.HV_BATTERY_MAX_TEMP).value() //
				+ "| Battery Temp: " + this.channel(RenaultZoeChannelId.HV_BATTERY_TEMP).value() //
				+ "| End of Charge Request " + this.channel(RenaultZoeChannelId.END_OF_CHARGE_REQUEST).value() //

		;

	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable( //
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				Battery.getModbusSlaveNatureTable(accessMode));
	}

}
