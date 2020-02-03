package io.openems.edge.project.renault.tmhmodbus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.TreeMap;

import com.ghgande.j2mod.modbus.procimg.Register;

import io.openems.common.channel.Level;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.element.BitsWordElement;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.ChannelId;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.modbusslave.ModbusRecord;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.refu88k.EssREFUstore88K;
import io.openems.edge.ess.refu88k.REFUStore88KChannelId;
import io.openems.edge.project.renault.battery.renaultzoe.BatteryRenaultZoe;
import io.openems.edge.project.renault.battery.renaultzoe.RenaultZoeChannelId;
import io.openems.edge.project.renault.tmhmodbus.types.ModbusSlaveDefinitionBuilder;

public class MyModbusSlaveDefinition {

	/**
	 * Initializes the specific Modbus-Slave table.
	 * 
	 * @return a map of modbus offset by record
	 */


	protected static TreeMap<Integer, ModbusRecord> getModbusSlaveDefinition(Sum sum, Collection<BatteryRenaultZoe> batteries, Collection<EssREFUstore88K> inverters, int technicalUnitId) {

		ModbusSlaveDefinitionBuilder b = ModbusSlaveDefinitionBuilder.of();
		
		
				/*************
				 * ESS-to-TMH
				 * 
				 * Implemented as "input registers" with address offset 1000. See
				 * https://en.wikipedia.org/wiki/Modbus#Modbus_object_types
				 *************/
				
				/**
				 * Beispiel
				 */
				
//				b.uint32(30000, "Technical Unit ID: Static Value", bat.get); //
//				b.uint32Supplier(30000, "Technical Unit ID: Static Value", () -> {return LocalDateTime.now().getSecond(); } );//

//				.uint16Supplier(30002, "System Status: System status of the TE, see Valid System States below", () -> {
//					Level state = sum.getState().value().asEnum();
//					if (state == Level.FAULT) {
//						return (short) 200; // "FAULT"
//					}
//					return (short) 150; // "ON"
//				}); //
//				
				
				/*
				 * Technical Unit Level Points
				 */
				
				b.uint32(1000, "Technical Unit ID: Static Value", technicalUnitId); //
				
				b.uint16Supplier(1002, "System Status: System status of the TE, see Valid System States below", () -> {
				Level state = sum.getState().value().asEnum();
				if (state == Level.FAULT) {
					return (short) SystemStatus.FAULT.getValue(); // "FAULT"
				}
				return (short) SystemStatus.ON.getValue(); // "ON"
				}); //
				
				b.uint32Supplier(1003, "Current Measrued Active Power", () -> {
					int activePower = 0;
					for (SymmetricEss ess : inverters) {
						try {
							activePower = activePower + ess.getActivePower().value().get();
						} catch (Exception e) {
							// TODO: handle exception
						}
					}
					return activePower;
				}); //
				
				b.uint32Supplier(1005, "Current Measrued Reactive Power", () -> {
					int reactivePower = 0;
					for (SymmetricEss ess : inverters) {
						try {
							reactivePower = reactivePower + ess.getReactivePower().value().get();
						} catch (Exception e) {
							// TODO: handle exception
						}
					}
					return reactivePower;
				}); //
				
				b.uint16Supplier(1007, "Alive Counter", () -> {
					return (short) LocalDateTime.now().getSecond();
				});
				
				b.uint32Supplier(1008, "Maximum Available Power Discharge", () -> {
					int availablePowerDischarge = 0;
					for(EssREFUstore88K ess : inverters) {
						availablePowerDischarge = availablePowerDischarge + ess.getAllowedDischarge().value().orElse(0);
					}
					return availablePowerDischarge;
				});
				
				b.uint32Supplier(1010, "Maximum Available Power Charge", () -> {
					int availablePowerCharge = 0;
					for(EssREFUstore88K ess : inverters) {
						try {
							availablePowerCharge = availablePowerCharge + Math.abs(ess.getAllowedCharge().value().get());
						} catch (Exception e) {
							// TODO: handle exception
						}
					}
					return availablePowerCharge;
				});
				
				b.uint32Supplier(1012, "Available Energy Discharge", () -> {
					int availableEnergyDischarge = 0;
					for(BatteryRenaultZoe bms : batteries) {
						try {
							availableEnergyDischarge = availableEnergyDischarge + bms.getAvailableEnergyDischarge().value().get();
						} catch (Exception e) {
							// TODO: handle exception
						}
					}
					return availableEnergyDischarge;
				});
				
				b.uint32Supplier(1014, "Available Energy Charge", () -> {
					int availableEnergyCharge = 0;
					for(BatteryRenaultZoe bms : batteries) {
						try {
							availableEnergyCharge = availableEnergyCharge + bms.getAvailableEnergyCharge().value().get();
						} catch (Exception e) {
							// TODO: handle exception
						}
					}
					return availableEnergyCharge;
				});
				
			
//				m(new BitsWordElement(SUNSPEC_103 + 41, this) //
//						.bit(0, REFUStore88KChannelId.OTHER_ALARM) //
//						.bit(1, REFUStore88KChannelId.OTHER_WARNING) //
//						),//
				
		b.uint16Supplier(1016, "Error Register 1", () -> {

			for (EssREFUstore88K ess : inverters) {
				
				
				int ret = 0 ;
				
				ret = ret | (  ((((BooleanReadChannel) ess.channel(REFUStore88KChannelId.TEST)).value().orElse(false) ?  1 : 0)) << 2  );
				
//				BitsWordElement bitWord = new BitsWordElement(1016, ess)
//						.bit(0, REFUStore88KChannelId.TEST)
//						;
//
//				Register r = bitWord.getRegister();
//				if (r == null) {
//					return (short) 0;
//				} else {
//					return (short) r.getValue();
//				}
				
				return (short) ret;
			}
			return (short) 0;
		});

//				b.uint32Supplier(1016, "Error Register 1", () -> {									
//					for(EssREFUstore88K ess : inverters) {
//						BitsWordElement bitWord = new BitsWordElement(1016, ess)
//								.bit(0, REFUStore88KChannelId.GROUND_FAULT)
//								.bit(1, REFUStore88KChannelId.DC_OVER_VOLTAGE);	
//						
//					}
//					
//				});
				

				
				
				
				/*
				 * Battery Level Points
				 */
					
//				for (BatteryRenaultZoe battery : batteries) {
//					b.uint32(0, "Battery ID: ", battery.getBatteryId());

//					for (Battery bat : batteries) {
//					b.float64(0, "asdf", bat.getCurrent().value().orElse(0));
//				}
					
					
//					b.uint16Supplier(1028, "Battery Pack Status: ", () -> {
//						Ren
//						EnumReadChannel powerRelayStateChannel = bat.channel(RenaultZoeChannelId.POWER_RELAY_STATE);
//						PowerRelayState powerRelayState = powerRelayStateChannel.value().asEnum();
//						
//						
//						if (powerRelayState == PowerRelayState.CLOSED) {
//							return (short) SystemStatus.ON.getValue();
//						}
//						return (short) 10;
//					}); //
					
					
//					b.uint32(1029, "SoC Battery: ", bat.getSoc().value().orElse(0));
//				}
				
				/*
				 * Battery Level Points
				 */
					
//				for (EssREFUstore88K inverter : inverters) {
//					b.uint32(0, "Battery ID: ", inverter.getInverterId());

					
					
					
//					b.uint16Supplier(1028, "Battery Pack Status: ", () -> {
//						Ren
//						EnumReadChannel powerRelayStateChannel = bat.channel(RenaultZoeChannelId.POWER_RELAY_STATE);
//						PowerRelayState powerRelayState = powerRelayStateChannel.value().asEnum();
//						
//						
//						if (powerRelayState == PowerRelayState.CLOSED) {
//							return (short) SystemStatus.ON.getValue();
//						}
//						return (short) 10;
//					}); //
					
					
//					b.uint32(1029, "SoC Battery: ", bat.getSoc().value().orElse(0));
//				}
				
				
				
				
				
				


				

				/*************
				 * TMH-to-ESS
				 * 
				 * Implemented as "holding registers" with address offset 40000. See
				 * https://en.wikipedia.org/wiki/Modbus#Modbus_object_types
				 *************/

				/*
				 * Technical Unit Level Points
				 */
//				.uint16Consumer(40000,
//						"System Status: The system state command from TMH to Technical Unit- reference table below",
//						(value) -> {
//							System.out.println("Trying to write [" + value + "] to System Status");
//						}); //
//
//		
//				for (Battery bat : batteries) {
//					b.float64(0, "asdf", bat.getCurrent().value().orElse(0));
//				}				
		return b.build();
	}
}
