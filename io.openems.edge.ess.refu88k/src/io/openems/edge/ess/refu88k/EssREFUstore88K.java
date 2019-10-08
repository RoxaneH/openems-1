package io.openems.edge.ess.refu88k;

import java.time.LocalDateTime;

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

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Ess.Refu.REFUstore88k", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE,//
		}

)
public class EssREFUstore88K extends AbstractOpenemsModbusComponent
		implements ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler, ModbusSlave {

	private final Logger log = LoggerFactory.getLogger(EssREFUstore88K.class);
	private Config config;

	public static final int DEFAULT_UNIT_ID = 1;
	private int MAX_APPARENT_POWER = 0;
	protected static final double EFFICIENCY_FACTOR = 0.98;

	/*
	 * Is Power allowed? This is set to false on error or if the inverter is not
	 * fully initialized.
	 */
	private boolean isPowerAllowed = false;
	private boolean isPowerRequired = false;

	private LocalDateTime timeNoPower;

	@Reference
	private Power power;

	@Reference
	protected ConfigurationAdmin cm;

	private Battery battery;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setBattery(Battery battery) {
		this.battery = battery;
	}

//	Konstruktor - Initialisierung der Channels
	public EssREFUstore88K() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				REFUStore88KChannelId.values() //
		);
		this.channel(SymmetricEss.ChannelId.MAX_APPARENT_POWER).setNextValue(MAX_APPARENT_POWER);
	}

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Activate
	void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled(), DEFAULT_UNIT_ID, this.cm, "Modbus",
				config.modbus_id()); //
		this.initializeBattery(config.battery_id());
		this.config = config;
		this.MAX_APPARENT_POWER = config.maxApparentPower();
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * Initializes the connection to the Battery.
	 * 
	 * @param servicePid this components' Service-PID
	 * @param batteryId  the Component-ID of the Battery component
	 */
	private void initializeBattery(String batteryId) {
		if (OpenemsComponent.updateReferenceFilter(this.cm, this.servicePid(), "Battery", batteryId)) {
			return;
		}

		this.battery.getSoc().onChange((oldValue, newValue) -> {
			this.getSoc().setNextValue(newValue.get());
			this.channel(REFUStore88KChannelId.BAT_SOC).setNextValue(newValue.get());
			this.channel(SymmetricEss.ChannelId.SOC).setNextValue(newValue.get());
		});

		this.battery.getVoltage().onChange((oldValue, newValue) -> {
			this.channel(REFUStore88KChannelId.BAT_VOLTAGE).setNextValue(newValue.get());
		});
	}

	private void handleStateMachine() {

		// by default: block Power
		this.isPowerAllowed = false;

		EnumReadChannel operatingStateChannel = this.channel(REFUStore88KChannelId.ST);
		OperatingState operatingState = operatingStateChannel.value().asEnum();

		switch (operatingState) {
		case OFF:
			/*
			 * 1) Inverter is OFF (St = 1), because no power is provided from the DC side.
			 * 2) The EMS has to initiate a precharge of the DC link capacities of the
			 * inverter 3) The EMS closes the DC relais of the battery 4) The inverter�s
			 * control board starts up (firmware booting) and enters the STANDBY state
			 * automatically
			 */
			break;
		case STANDBY:
			/*
			 * The inverter is initialised but not grid connected. The IGBT's are locked and
			 * AC relays are open.
			 */
			this.doStandbyHandling();
			break;
		case SLEEPING:
			break;
		case STARTING:
			/*
			 * The inverter is connecting to the grid. The inverter switches to STARTED
			 * automatically after few seconds.
			 */
			break;
		case STARTED:
			/*
			 * The inverter is grid connected. AC Relays are closed. The Hardware (IGBT's)
			 * are locked.
			 */
			this.checkIfPowerIsAllowed();
			this.doGridConnectedHandling();
			break;
		case THROTTLED:
			/*
			 * The inverter feeds and derating is active. The IGBT's are working and AC
			 * relays are closed.
			 */
			this.checkIfPowerIsAllowed();
			this.timeNoPowerRequired();
			break;
		case MPPT:
			/*
			 * The inverter feeds with max possible power. The IGBT's are working and AC
			 * relays are closed.
			 */
			this.checkIfPowerIsAllowed();
			this.timeNoPowerRequired();
			break;
		case SHUTTING_DOWN:
			/*
			 * The inverter is shutting down. The IGBT's are locked and AC relays are open.
			 */
			break;
		case FAULT:
			/*
			 * The inverter is in fault state. The IGBT's are locked and AC relays are open.
			 */
			break;
		case UNDEFINED:
			// Do nothing because these states are only temporarily reached
			break;
		}
	}

	private void offHandleStateMachine() {

		EnumReadChannel operatingStateChannel = this.channel(REFUStore88KChannelId.ST);
		OperatingState operatingState = operatingStateChannel.value().asEnum();

		switch (operatingState) {
		case OFF:
			break;
		case STANDBY:
			break;
		case SLEEPING:
			break;
		case STARTING:
			this.stopInverter();
			break;
		case STARTED:
			this.stopInverter();
			break;
		case THROTTLED:
			this.stopInverter();
			break;
		case MPPT:
			this.stopInverter();
			break;
		case SHUTTING_DOWN:
			break;
		case FAULT:
			break;
		case UNDEFINED:
			// Do nothing because these states are only temporarily reached
			break;
		}
	}

	private void checkIfPowerIsAllowed() {
		// If the battery system is not ready yet set power to zero to avoid damaging or
		// improper system states
		this.isPowerAllowed = battery.getReadyForWorking().value().orElse(false);

		// Read important Channels from battery
		int optV = battery.getVoltage().value().orElse(0);
		int disMaxA = battery.getDischargeMaxCurrent().value().orElse(0);
		int chaMaxA = battery.getChargeMaxCurrent().value().orElse(0);

		// Calculate absolute Value allowedCharge and allowed Discharge from battery
		double absAllowedCharge = Math.abs(chaMaxA * optV * EFFICIENCY_FACTOR);
		double absAllowedDischarge = Math.abs(disMaxA * optV * EFFICIENCY_FACTOR);

		// Determine allowedCharge and allowedDischarge from Inverter
		if (absAllowedCharge > MAX_APPARENT_POWER) {
			this.getAllowedCharge().setNextValue(MAX_APPARENT_POWER * -1);
		} else {
			this.getAllowedCharge().setNextValue(absAllowedCharge * -1);
		}

		if (absAllowedDischarge > MAX_APPARENT_POWER) {
			this.getAllowedDischarge().setNextValue(MAX_APPARENT_POWER);
		} else {
			this.getAllowedDischarge().setNextValue(absAllowedDischarge);
		}
	}

	private void checkIfPowerIsRequired(int activePower, int reactivePower) {

		if (activePower != 0 || reactivePower != 0) {
			this.isPowerRequired = true;
		} else {
			this.isPowerRequired = false;
		}

	}

	private void timeNoPowerRequired() {
		if (!isPowerRequired) {
			if (timeNoPower == null) {
				timeNoPower = LocalDateTime.now();
			}
			if ((timeNoPower.plusSeconds(config.timeLimitNoPower())).isBefore(LocalDateTime.now())) {
				this.enterStartedMode();
			}
		} else {
			timeNoPower = null;
		}
	}

	private void doStandbyHandling() {
		this.isPowerAllowed = false;
		this.exitStandbyMode();
	}

	private void exitStandbyMode() {
		EnumWriteChannel pcsSetOperation = this.channel(REFUStore88KChannelId.PCS_SET_OPERATION);
		try {
			pcsSetOperation.setNextWriteValue(PCSSetOperation.EXIT_STANDBY_MODE);
		} catch (OpenemsNamedException e) {
			log.error("problem occurred while trying to start grid mode" + e.getMessage());
		}
	}

	private void doGridConnectedHandling() {
		checkIfPowerIsAllowed();
		if (isPowerRequired && isPowerAllowed) {
			EnumWriteChannel pcsSetOperation = this.channel(REFUStore88KChannelId.PCS_SET_OPERATION);
			try {
				pcsSetOperation.setNextWriteValue(PCSSetOperation.CONNECT_TO_GRID);
			} catch (OpenemsNamedException e) {
				log.error("problem occurred while trying to start grid mode" + e.getMessage());
			}
		}

	}

	private void enterStartedMode() {
		EnumWriteChannel pcsSetOperation = this.channel(REFUStore88KChannelId.PCS_SET_OPERATION);
		try {
			pcsSetOperation.setNextWriteValue(PCSSetOperation.ENTER_STARTED_MODE);
		} catch (OpenemsNamedException e) {
			log.error("problem occurred while trying to start grid mode" + e.getMessage());
		}
	}

	private void stopInverter() {

		this.isPowerAllowed = false;

		IntegerWriteChannel wMaxLimPctChannel = this.channel(REFUStore88KChannelId.W_MAX_LIM_PCT);
		EnumWriteChannel wMaxLim_EnaChannel = this.channel(REFUStore88KChannelId.W_MAX_LIM_ENA);

		IntegerWriteChannel varMaxLimPctChannel = this.channel(REFUStore88KChannelId.VAR_W_MAX_PCT);
		EnumWriteChannel varMaxLim_EnaChannel = this.channel(REFUStore88KChannelId.VAR_PCT_ENA);

		// Set Active Power to Zero
		try {
			wMaxLimPctChannel.setNextWriteValue(0);
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			wMaxLim_EnaChannel.setNextWriteValue(WMaxLimEna.ENABLED);
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Set Reactive Power to Zero
		try {
			varMaxLimPctChannel.setNextWriteValue(0);
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		try {
			varMaxLim_EnaChannel.setNextWriteValue(VArPctEna.ENABLED);
		} catch (OpenemsNamedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		int activePower = Math.abs(getActivePower().value().orElse(111));
		int reactivePower = Math.abs(getReactivePower().value().orElse(111));

		if (activePower <= 110 && reactivePower <= 110) {
			EnumWriteChannel pcsSetOperation = this.channel(REFUStore88KChannelId.PCS_SET_OPERATION);
			try {
				pcsSetOperation.setNextWriteValue(PCSSetOperation.ENTER_STANDBY_MODE);
			} catch (OpenemsNamedException e) {
				log.error("problem occurred while trying to start grid mode" + e.getMessage());
			}
		} else {
			return;
		}

	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable( //
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			if (!config.inverterOff()) {
				this.handleStateMachine();
			} else {
				this.offHandleStateMachine();
			}
			break;
		}
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {

		if (!this.isPowerAllowed) {
			this.log.debug("Power is not allowed!");
			return;
		}

		IntegerWriteChannel wMaxChannel = this.channel(REFUStore88KChannelId.W_MAX);
		wMaxChannel.setNextWriteValue(MAX_APPARENT_POWER); // Set WMax

		IntegerWriteChannel wMaxLimPctChannel = this.channel(REFUStore88KChannelId.W_MAX_LIM_PCT);
		EnumWriteChannel wMaxLim_EnaChannel = this.channel(REFUStore88KChannelId.W_MAX_LIM_ENA);

		IntegerWriteChannel varMaxLimPctChannel = this.channel(REFUStore88KChannelId.VAR_W_MAX_PCT);
		EnumWriteChannel varMaxLim_EnaChannel = this.channel(REFUStore88KChannelId.VAR_PCT_ENA);

		this.checkIfPowerIsRequired(activePower, reactivePower);

		/*
		 * Set Active Power as a percentage of WMAX
		 */
		int wSetPct = ((100 * activePower) / MAX_APPARENT_POWER);
		wMaxLimPctChannel.setNextWriteValue(wSetPct);
		wMaxLim_EnaChannel.setNextWriteValue(WMaxLimEna.ENABLED);

		/*
		 * Set Reactive Power as a percentage of WMAX
		 */
		int varSetPct = ((100 * reactivePower) / MAX_APPARENT_POWER);
		varMaxLimPctChannel.setNextWriteValue(varSetPct);
		varMaxLim_EnaChannel.setNextWriteValue(VArPctEna.ENABLED);

	}

	@Override
	public Constraint[] getStaticConstraints() throws OpenemsException {
		if (this.isPowerAllowed) {
			return Power.NO_CONSTRAINTS;
		} else {
			return new Constraint[] {
					this.createPowerConstraint("REFU inverter not ready", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS,
							0),
					this.createPowerConstraint("Reactive power is not allowed", Phase.ALL, Pwr.REACTIVE,
							Relationship.EQUALS, 0) };
		}
	}

	@Override
	public int getPowerPrecision() {
		return MAX_APPARENT_POWER / 100;
	}

	/*
	 * Supported Models First available Model = Start Address + 2 = 40002 Then 40002
	 * + Length of Model ....
	 */
	private final static int START_ADDRESS = 40000;
	private final static int SUNSPEC_1 = START_ADDRESS + 2; // Common
	private final static int SUNSPEC_103 = 40070; // Inverter (Three Phase)
	private final static int SUNSPEC_120 = 40122; // Nameplate
	private final static int SUNSPEC_121 = 40150; // Basic Settings
	private final static int SUNSPEC_123 = 40182; // Immediate Controls
	private final static int SUNSPEC_64040 = 40208; // REFU Parameter
	private final static int SUNSPEC_64041 = 40213; // REFU Parameter Value
	private final static int SUNSPEC_64800 = 40225; // MESA-PCS Extensions

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(SUNSPEC_1, Priority.ONCE, //
						m(REFUStore88KChannelId.ID_1, new UnsignedWordElement(SUNSPEC_1)),
						m(REFUStore88KChannelId.L_1, new UnsignedWordElement(SUNSPEC_1 + 1)),
						m(REFUStore88KChannelId.MN, new StringWordElement(SUNSPEC_1 + 2, 16)),
						m(REFUStore88KChannelId.MD, new StringWordElement(SUNSPEC_1 + 18, 16)),
						m(REFUStore88KChannelId.OPT, new StringWordElement(SUNSPEC_1 + 34, 8)),
						m(REFUStore88KChannelId.VR, new StringWordElement(SUNSPEC_1 + 42, 8)),
						m(REFUStore88KChannelId.SN, new StringWordElement(SUNSPEC_1 + 50, 16)),
						m(REFUStore88KChannelId.DA, new UnsignedWordElement(SUNSPEC_1 + 66))),

				new FC3ReadRegistersTask(SUNSPEC_103, Priority.LOW, //
						m(REFUStore88KChannelId.ID_103, new UnsignedWordElement(SUNSPEC_103)),
						m(REFUStore88KChannelId.L_103, new UnsignedWordElement(SUNSPEC_103 + 1)),
						m(REFUStore88KChannelId.A, new UnsignedWordElement(SUNSPEC_103 + 2),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.APH_A, new UnsignedWordElement(SUNSPEC_103 + 3),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.APH_B, new UnsignedWordElement(SUNSPEC_103 + 4),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.APH_C, new UnsignedWordElement(SUNSPEC_103 + 5),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.A_SF, new UnsignedWordElement(SUNSPEC_103 + 6)),
						m(REFUStore88KChannelId.PP_VPH_AB, new UnsignedWordElement(SUNSPEC_103 + 7),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.PP_VPH_BC, new UnsignedWordElement(SUNSPEC_103 + 8),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.PP_VPH_CA, new UnsignedWordElement(SUNSPEC_103 + 9),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.PH_VPH_A, new UnsignedWordElement(SUNSPEC_103 + 10),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.PH_VPH_B, new UnsignedWordElement(SUNSPEC_103 + 11),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.PH_VPH_B, new UnsignedWordElement(SUNSPEC_103 + 12),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.V_SF, new UnsignedWordElement(SUNSPEC_103 + 13)),
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(SUNSPEC_103 + 14),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.W_SF, new SignedWordElement(SUNSPEC_103 + 15)),
						m(REFUStore88KChannelId.HZ, new SignedWordElement(SUNSPEC_103 + 16),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.HZ_SF, new SignedWordElement(SUNSPEC_103 + 17)),
						m(REFUStore88KChannelId.VA, new SignedWordElement(SUNSPEC_103 + 18),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VA_SF, new SignedWordElement(SUNSPEC_103 + 19)),
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedWordElement(SUNSPEC_103 + 20),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VA_R_SF, new SignedWordElement(SUNSPEC_103 + 21)),
						new DummyRegisterElement(SUNSPEC_103 + 22, SUNSPEC_103 + 23),
						m(REFUStore88KChannelId.WH, new UnsignedDoublewordElement(SUNSPEC_103 + 24),
								ElementToChannelConverter.SCALE_FACTOR_2),
						m(REFUStore88KChannelId.WH_SF, new UnsignedWordElement(SUNSPEC_103 + 26)),
						m(REFUStore88KChannelId.DCA, new UnsignedWordElement(SUNSPEC_103 + 27),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.DCA_SF, new UnsignedWordElement(SUNSPEC_103 + 28)),
						m(REFUStore88KChannelId.DCV, new UnsignedWordElement(SUNSPEC_103 + 29),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.DCV_SF, new UnsignedWordElement(SUNSPEC_103 + 30)),
						m(REFUStore88KChannelId.DCW, new SignedWordElement(SUNSPEC_103 + 31),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.DCW_SF, new SignedWordElement(SUNSPEC_103 + 32)),
						m(REFUStore88KChannelId.TMP_CAB, new SignedWordElement(SUNSPEC_103 + 33),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						m(REFUStore88KChannelId.TMP_SNK, new SignedWordElement(SUNSPEC_103 + 34),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1),
						new DummyRegisterElement(SUNSPEC_103 + 35, SUNSPEC_103 + 36),
						m(REFUStore88KChannelId.TMP_SF, new UnsignedWordElement(SUNSPEC_103 + 37)),
						m(REFUStore88KChannelId.ST, new UnsignedWordElement(SUNSPEC_103 + 38)),
						m(REFUStore88KChannelId.ST_VND, new UnsignedWordElement(SUNSPEC_103 + 39)),
						m(REFUStore88KChannelId.EVT_1, new UnsignedDoublewordElement(SUNSPEC_103 + 40)),
						m(REFUStore88KChannelId.EVT_2, new UnsignedDoublewordElement(SUNSPEC_103 + 42)),
						m(REFUStore88KChannelId.EVT_VND_1, new UnsignedDoublewordElement(SUNSPEC_103 + 44)),
						m(REFUStore88KChannelId.EVT_VND_2, new UnsignedDoublewordElement(SUNSPEC_103 + 46)),
						m(REFUStore88KChannelId.EVT_VND_3, new UnsignedDoublewordElement(SUNSPEC_103 + 48)),
						m(REFUStore88KChannelId.EVT_VND_4, new UnsignedDoublewordElement(SUNSPEC_103 + 50))),

				new FC3ReadRegistersTask(SUNSPEC_120, Priority.LOW, //
						m(REFUStore88KChannelId.ID_120, new UnsignedWordElement(SUNSPEC_120)),
						m(REFUStore88KChannelId.L_120, new UnsignedWordElement(SUNSPEC_120 + 1)),
						m(REFUStore88KChannelId.DER_TYP, new UnsignedWordElement(SUNSPEC_120 + 2)),
						m(REFUStore88KChannelId.W_RTG, new UnsignedWordElement(SUNSPEC_120 + 3),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.W_RTG_SF, new UnsignedWordElement(SUNSPEC_120 + 4)),
						m(REFUStore88KChannelId.VA_RTG, new UnsignedWordElement(SUNSPEC_120 + 5),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VA_RTG_SF, new UnsignedWordElement(SUNSPEC_120 + 6)),
						m(REFUStore88KChannelId.VAR_RTG_Q1, new SignedWordElement(SUNSPEC_120 + 7),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VAR_RTG_Q2, new SignedWordElement(SUNSPEC_120 + 8),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VAR_RTG_Q3, new SignedWordElement(SUNSPEC_120 + 9),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VAR_RTG_Q4, new SignedWordElement(SUNSPEC_120 + 10),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.VAR_RTG_SF, new SignedWordElement(SUNSPEC_120 + 11)),
						m(REFUStore88KChannelId.A_RTG, new UnsignedWordElement(SUNSPEC_120 + 12),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_2),
						m(REFUStore88KChannelId.A_RTG_SF, new SignedWordElement(SUNSPEC_120 + 13)),
						m(REFUStore88KChannelId.PF_RTG_Q1, new SignedWordElement(SUNSPEC_120 + 14),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3),
						m(REFUStore88KChannelId.PF_RTG_Q2, new SignedWordElement(SUNSPEC_120 + 15),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3),
						m(REFUStore88KChannelId.PF_RTG_Q3, new SignedWordElement(SUNSPEC_120 + 16),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3),
						m(REFUStore88KChannelId.PF_RTG_Q4, new SignedWordElement(SUNSPEC_120 + 17),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3),
						m(REFUStore88KChannelId.PF_RTG_SF, new SignedWordElement(SUNSPEC_120 + 18))),

				new FC3ReadRegistersTask(SUNSPEC_121, Priority.LOW, //
						m(REFUStore88KChannelId.ID_121, new UnsignedWordElement(SUNSPEC_121)),
						m(REFUStore88KChannelId.L_121, new UnsignedWordElement(SUNSPEC_121 + 1))),
				new FC3ReadRegistersTask(SUNSPEC_121 + 22, Priority.LOW, //
						m(REFUStore88KChannelId.W_MAX_SF, new UnsignedWordElement(SUNSPEC_121 + 22)),
						m(REFUStore88KChannelId.V_REF_SF, new UnsignedWordElement(SUNSPEC_121 + 23)),
						m(REFUStore88KChannelId.V_REF_OFS_SF, new UnsignedWordElement(SUNSPEC_121 + 24))),

				new FC16WriteRegistersTask(SUNSPEC_121 + 2, //
						m(REFUStore88KChannelId.W_MAX, new UnsignedWordElement(SUNSPEC_121 + 2),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.V_REF, new UnsignedWordElement(SUNSPEC_121 + 3),
								ElementToChannelConverter.SCALE_FACTOR_1),
						m(REFUStore88KChannelId.V_REF_OFS, new UnsignedWordElement(SUNSPEC_121 + 4),
								ElementToChannelConverter.SCALE_FACTOR_1)),

				new FC3ReadRegistersTask(SUNSPEC_123, Priority.LOW, //
						m(REFUStore88KChannelId.ID_123, new UnsignedWordElement(SUNSPEC_123)),
						m(REFUStore88KChannelId.L_123, new UnsignedWordElement(SUNSPEC_123 + 1))),
				new FC3ReadRegistersTask(SUNSPEC_123 + 23, Priority.LOW, //
						m(REFUStore88KChannelId.W_MAX_LIM_PCT_SF, new UnsignedWordElement(SUNSPEC_123 + 23)),
						m(REFUStore88KChannelId.OUT_PF_SET_SF, new UnsignedWordElement(SUNSPEC_123 + 24)),
						m(REFUStore88KChannelId.VAR_PCT_SF, new UnsignedWordElement(SUNSPEC_123 + 25))),

				new FC16WriteRegistersTask(SUNSPEC_123 + 4, //
						m(REFUStore88KChannelId.CONN, new UnsignedWordElement(SUNSPEC_123 + 4)),

						m(REFUStore88KChannelId.W_MAX_LIM_PCT, new SignedWordElement(SUNSPEC_123 + 5), // W_MAX_LIM_PCT
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),

				new FC16WriteRegistersTask(SUNSPEC_123 + 9, //
						m(REFUStore88KChannelId.W_MAX_LIM_ENA, new UnsignedWordElement(SUNSPEC_123 + 9)),
						m(REFUStore88KChannelId.OUT_PF_SET, new SignedWordElement(SUNSPEC_123 + 10),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_3)),
				new FC16WriteRegistersTask(SUNSPEC_123 + 14, //
						m(REFUStore88KChannelId.OUT_PF_SET_ENA, new UnsignedWordElement(SUNSPEC_123 + 14)),
						m(REFUStore88KChannelId.VAR_W_MAX_PCT, new SignedWordElement(SUNSPEC_123 + 15),
								ElementToChannelConverter.SCALE_FACTOR_MINUS_1)),
				new FC16WriteRegistersTask(SUNSPEC_123 + 22, //
						m(REFUStore88KChannelId.VAR_PCT_ENA, new UnsignedWordElement(SUNSPEC_123 + 22))),

				new FC3ReadRegistersTask(SUNSPEC_64040, Priority.LOW, //
						m(REFUStore88KChannelId.ID_64040, new UnsignedWordElement(SUNSPEC_64040)),
						m(REFUStore88KChannelId.L_64040, new UnsignedWordElement(SUNSPEC_64040 + 1))),

				new FC16WriteRegistersTask(SUNSPEC_64040 + 2, //
						m(REFUStore88KChannelId.READ_WRITE_PARAM_ID, new UnsignedDoublewordElement(SUNSPEC_64040 + 2)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_INDEX,
								new UnsignedDoublewordElement(SUNSPEC_64040 + 4))),

				new FC3ReadRegistersTask(SUNSPEC_64041, Priority.LOW, //
						m(REFUStore88KChannelId.ID_64041, new UnsignedWordElement(SUNSPEC_64041)),
						m(REFUStore88KChannelId.L_64041, new UnsignedWordElement(SUNSPEC_64041 + 1))),

				new FC16WriteRegistersTask(SUNSPEC_64041 + 2, //
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_U32,
								new UnsignedDoublewordElement(SUNSPEC_64041 + 2)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_S32,
								new SignedDoublewordElement(SUNSPEC_64041 + 4)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_F32,
								new SignedDoublewordElement(SUNSPEC_64041 + 6)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_U16, new UnsignedWordElement(SUNSPEC_64041 + 8)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_S16, new SignedWordElement(SUNSPEC_64041 + 9)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_U8, new UnsignedWordElement(SUNSPEC_64041 + 10)),
						m(REFUStore88KChannelId.READ_WRITE_PARAM_VALUE_S8, new SignedWordElement(SUNSPEC_64041 + 11))),

				new FC16WriteRegistersTask(SUNSPEC_64800 + 6, //
						m(REFUStore88KChannelId.PCS_SET_OPERATION, new SignedWordElement(SUNSPEC_64800 + 6))));

	}

	@Override
	public String debugLog() {
		return "State:" + this.channel(REFUStore88KChannelId.ST).value().asOptionString() //
				+ " | Active Power:" + this.channel(SymmetricEss.ChannelId.ACTIVE_POWER).value().asString() //
				+ " | Reactive Power:" + this.channel(SymmetricEss.ChannelId.REACTIVE_POWER).value().asString() //
				+ " | Allowed Charge:" + this.getAllowedCharge().value() //
				+ " | Allowed Discharge:" + this.getAllowedDischarge().value() //
				+ " | Allowed ChargeCurrent:" + this.battery.getChargeMaxCurrent() //
				+ " | Allowed DischargeCurrent:" + this.battery.getDischargeMaxCurrent() //
		;
	}
}
