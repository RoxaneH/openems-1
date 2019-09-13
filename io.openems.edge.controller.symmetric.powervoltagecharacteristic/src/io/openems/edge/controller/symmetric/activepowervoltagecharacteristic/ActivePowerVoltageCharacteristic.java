package io.openems.edge.controller.symmetric.activepowervoltagecharacteristic;

import java.util.HashMap;
import java.util.Map;

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
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.OpenemsType;
import io.openems.common.utils.JsonUtils;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.meter.api.SymmetricMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "Controller.Symmetric.ActivePowerVoltageCharacteristic", //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ActivePowerVoltageCharacteristic extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

    private final Logger log = LoggerFactory.getLogger(ActivePowerVoltageCharacteristic.class);
    private final Map<Float, Float> voltagePowerMap = new HashMap<>();

    /**
     * nominal voltage in [mV].
     */
    private float nominalVoltage;
    private Config config;
    private int power = 0;

    @Reference
    protected ConfigurationAdmin cm;

    @Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, //
            cardinality = ReferenceCardinality.MANDATORY)
    private SymmetricMeter meter;

    @Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
    private ManagedSymmetricEss ess;

    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

        CALCULATED_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), //
        PERCENT(Doc.of(OpenemsType.FLOAT).unit(Unit.PERCENT)), //
        VOLTAGE_RATIO(Doc.of(OpenemsType.DOUBLE))//

        ;
        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }

    public ActivePowerVoltageCharacteristic() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                Controller.ChannelId.values(), //
                ChannelId.values() //
        );
    }

    @Activate
    void activate(ComponentContext context, Config config) throws OpenemsNamedException {
        super.activate(context, config.id(), config.alias(), config.enabled());
        if (OpenemsComponent.updateReferenceFilter(cm, this.servicePid(), "ess", config.ess_id())) {
            return;
        }
        if (OpenemsComponent.updateReferenceFilter(cm, this.servicePid(), "meter", config.meter_id())) {
            return;
        }
        this.config = config;
        this.nominalVoltage = config.nominalVoltage() * 1000;

    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    /**
     * Initialize the P by U characteristics.
     *
     * <p>
     * Parsing JSON then putting the point variables into pByUCharacteristicEquation
     *
     * <pre>
     * [
     *  { "voltageRatio": 0.9,      "power" : -4000 }},
     *  { "voltageRatio": 0.93,     "power": -1000 }},
     *  { "voltageRatio": 1.07,     "power": 0}},
     *  { "voltageRatio": 1.1,      "power": 1000 } }
     * ]
     * </pre>
     * 
     * @param gridVoltageRatio voltage ratio of measured grid voltage from grid
     *                         meter over nominal voltage
     * @param powerConf        configuration which is given by user to determine
     *                         point with related powers
     * 
     * @throws OpenemsNamedException on error
     */

    private void initialize(String powerConf, float gridVoltageRatio) throws OpenemsNamedException {
        try {
            JsonArray jpowerV = JsonUtils.getAsJsonArray(JsonUtils.parse(powerConf));
            for (JsonElement element : jpowerV) {
                Float power = (float) JsonUtils.getAsInt(element, "power");
                float voltageRatio = JsonUtils.getAsFloat(element, "voltageRatio");
                this.voltagePowerMap.put(voltageRatio, power);
            }
        } catch (NullPointerException e) {
            throw new OpenemsException("Unable to set values [" + powerConf + "] " + e.getMessage());
        }
    }

    @Override
    public void run() throws OpenemsNamedException {
        float voltageRatio = this.meter.getVoltage().value().orElse(0) / this.nominalVoltage;
        this.channel(ChannelId.VOLTAGE_RATIO).setNextValue(voltageRatio);
        if (voltageRatio == 0) {
            log.info("Voltage Ratio is 0 ");
            return;
        }
        this.initialize(config.powerV(), voltageRatio);
        float linePowerValue = Utils.getValueOfLine(this.voltagePowerMap, voltageRatio);
        if (linePowerValue == 0) {
            log.info("Voltage in the Safe Zone; Power will not set in power voltage characteristic controller");
            this.channel(ChannelId.CALCULATED_POWER).setNextValue(-1);
            return;
        }
        this.power = (int) linePowerValue;
        int calculatedPower = 0;
        calculatedPower = ess.getPower().fitValueIntoMinMaxPower(this.id(), ess, Phase.ALL, Pwr.ACTIVE, this.power);
        this.channel(ChannelId.CALCULATED_POWER).setNextValue(calculatedPower);
        this.ess.addPowerConstraintAndValidate("ActivePowerVoltageCharacteristic", Phase.ALL, Pwr.ACTIVE,
                Relationship.EQUALS, calculatedPower);
    }
}