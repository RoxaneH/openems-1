package io.openems.edge.project.renault.tmhmodbus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
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
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.modbusslave.ModbusRecord;
import io.openems.edge.common.sum.Sum;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.refu88k.EssREFUstore88K;
import io.openems.edge.ess.refu88k.OperatingState;
import io.openems.edge.ess.refu88k.REFUStore88KChannelId;
import io.openems.edge.project.renault.battery.renaultzoe.BatteryRenaultZoe;
import io.openems.edge.project.renault.battery.renaultzoe.RenaultZoeChannelId;
import io.openems.edge.project.renault.battery.renaultzoe.State;
import io.openems.edge.project.renault.tmhmodbus.types.ModbusSlaveDefinitionBuilder;

public class MyModbusSlaveDefinition {

	/**
	 * Initializes the specific Modbus-Slave table.
	 * 
	 * @return a map of modbus offset by record
	 */
	

	protected static TreeMap<Integer, ModbusRecord> getModbusSlaveDefinition(ComponentManager componentManager, Sum sum,
			List<BatteryRenaultZoe> batteries, List<EssREFUstore88K> inverters, int technicalUnitId) {

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
			for (EssREFUstore88K ess : inverters) {
				availablePowerDischarge = availablePowerDischarge + ess.getAllowedDischarge().value().orElse(0);
			}
			return availablePowerDischarge;
		});

		b.uint32Supplier(1010, "Maximum Available Power Charge", () -> {
			int availablePowerCharge = 0;
			for (EssREFUstore88K ess : inverters) {
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
			for (BatteryRenaultZoe bms : batteries) {
				try {
					availableEnergyDischarge = availableEnergyDischarge
							+ bms.getAvailableEnergyDischarge().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
			}
			return availableEnergyDischarge;
		});

		b.uint32Supplier(1014, "Available Energy Charge", () -> {
			int availableEnergyCharge = 0;
			for (BatteryRenaultZoe bms : batteries) {
				try {
					availableEnergyCharge = availableEnergyCharge + bms.getAvailableEnergyCharge().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
			}
			return availableEnergyCharge;
		});

		b.uint32Supplier(1016, "Error Register 1", () -> {
			int bitWord = 0;
			boolean test = false;

			try {
				bitWord = bitWord | (((test ? 1 : 0)) << 0);
				bitWord = bitWord | (((test ? 1 : 0)) << 1);
				bitWord = bitWord | (((test ? 1 : 0)) << 3);
				bitWord = bitWord | (((test ? 1 : 0)) << 4);
				bitWord = bitWord | (((test ? 1 : 0)) << 5);
				bitWord = bitWord | (((test ? 1 : 0)) << 6);
				bitWord = bitWord | (((test ? 1 : 0)) << 7);
				bitWord = bitWord | (((test ? 1 : 0)) << 8);
				bitWord = bitWord | (((test ? 1 : 0)) << 9);
				bitWord = bitWord | (((test ? 1 : 0)) << 10);
				bitWord = bitWord | (((test ? 1 : 0)) << 11);
				bitWord = bitWord | (((test ? 1 : 0)) << 12);

				for (BatteryRenaultZoe bms : batteries) {
					bitWord = bitWord | (((bms.isFailure1() ? 1 : 0)) << 13);
					bitWord = bitWord | (((bms.isFailure2() ? 1 : 0)) << 14);
					bitWord = bitWord | (((test ? 1 : 0)) << 15);
					bitWord = bitWord | (((test ? 1 : 0)) << 16);
					bitWord = bitWord | (((test ? 1 : 0)) << 17);
					bitWord = bitWord | (((test ? 1 : 0)) << 18);
					bitWord = bitWord | (((test ? 1 : 0)) << 19);
					bitWord = bitWord | (((test ? 1 : 0)) << 20);
					bitWord = bitWord | (((test ? 1 : 0)) << 21);
					bitWord = bitWord | (((test ? 1 : 0)) << 22);
					bitWord = bitWord | (((test ? 1 : 0)) << 23);
					bitWord = bitWord | (((test ? 1 : 0)) << 24);
					bitWord = bitWord | (((test ? 1 : 0)) << 25);
					bitWord = bitWord | (((test ? 1 : 0)) << 26);
					bitWord = bitWord | (((test ? 1 : 0)) << 27);
					bitWord = bitWord | (((test ? 1 : 0)) << 28);
					bitWord = bitWord | (((test ? 1 : 0)) << 29);
					bitWord = bitWord | (((test ? 1 : 0)) << 30);
					bitWord = bitWord | (((test ? 1 : 0)) << 31);
				}
			} catch (Exception e) {
				// TODO: handle exception
			}
			return bitWord;
		});

		b.uint32Supplier(1018, "Error Register 2", () -> {
			int bitWord = 0;
			boolean test = false;

			try {
				for (BatteryRenaultZoe bms : batteries) {
					bitWord = bitWord | (((test ? 1 : 0)) << 0);
					bitWord = bitWord | (((test ? 1 : 0)) << 1);
					bitWord = bitWord | (((test ? 1 : 0)) << 3);
					bitWord = bitWord | (((test ? 1 : 0)) << 4);
					bitWord = bitWord | (((test ? 1 : 0)) << 5);
					bitWord = bitWord | (((test ? 1 : 0)) << 6);
					bitWord = bitWord | (((test ? 1 : 0)) << 7);
					bitWord = bitWord | (((test ? 1 : 0)) << 8);
					bitWord = bitWord | (((test ? 1 : 0)) << 9);
					bitWord = bitWord | (((test ? 1 : 0)) << 10);
					bitWord = bitWord | (((test ? 1 : 0)) << 11);
					bitWord = bitWord | (((test ? 1 : 0)) << 12);
					bitWord = bitWord | (((test ? 1 : 0)) << 13);
					bitWord = bitWord | (((test ? 1 : 0)) << 14);
					bitWord = bitWord | (((test ? 1 : 0)) << 15);
					bitWord = bitWord | (((test ? 1 : 0)) << 16);
					bitWord = bitWord | (((test ? 1 : 0)) << 17);
					bitWord = bitWord | (((test ? 1 : 0)) << 18);
					bitWord = bitWord | (((test ? 1 : 0)) << 19);
					bitWord = bitWord | (((test ? 1 : 0)) << 20);
					bitWord = bitWord | (((test ? 1 : 0)) << 21);
					bitWord = bitWord | (((test ? 1 : 0)) << 22);
					bitWord = bitWord | (((test ? 1 : 0)) << 23);
					bitWord = bitWord | (((test ? 1 : 0)) << 24);
					bitWord = bitWord | (((test ? 1 : 0)) << 25);
					bitWord = bitWord | (((test ? 1 : 0)) << 26);
					bitWord = bitWord | (((test ? 1 : 0)) << 27);
				}
				for (EssREFUstore88K ess : inverters) {
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 28);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 29);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 30);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 31);
				}
			} catch (Exception e) {
				// TODO: handle exception
			}
			return bitWord;
		});

		b.uint32Supplier(1020, "Error Register 3", () -> {
			int bitWord = 0;
			boolean test = false;

			try {
				for (BatteryRenaultZoe bms : batteries) {
					bitWord = bitWord | (((test ? 1 : 0)) << 0);
					bitWord = bitWord | (((test ? 1 : 0)) << 1);
					bitWord = bitWord | (((test ? 1 : 0)) << 3);
					bitWord = bitWord | (((test ? 1 : 0)) << 4);
					bitWord = bitWord | (((test ? 1 : 0)) << 5);
					bitWord = bitWord | (((test ? 1 : 0)) << 6);
					bitWord = bitWord | (((test ? 1 : 0)) << 7);
					bitWord = bitWord | (((test ? 1 : 0)) << 8);
					bitWord = bitWord | (((test ? 1 : 0)) << 9);
					bitWord = bitWord | (((test ? 1 : 0)) << 10);
					bitWord = bitWord | (((test ? 1 : 0)) << 11);
					bitWord = bitWord | (((test ? 1 : 0)) << 12);
					bitWord = bitWord | (((test ? 1 : 0)) << 13);
					bitWord = bitWord | (((test ? 1 : 0)) << 14);
					bitWord = bitWord | (((test ? 1 : 0)) << 15);
					bitWord = bitWord | (((test ? 1 : 0)) << 16);
					bitWord = bitWord | (((test ? 1 : 0)) << 17);
					bitWord = bitWord | (((test ? 1 : 0)) << 18);
					bitWord = bitWord | (((test ? 1 : 0)) << 19);
					bitWord = bitWord | (((test ? 1 : 0)) << 20);
					bitWord = bitWord | (((test ? 1 : 0)) << 21);
					bitWord = bitWord | (((test ? 1 : 0)) << 22);
					bitWord = bitWord | (((test ? 1 : 0)) << 23);
					bitWord = bitWord | (((test ? 1 : 0)) << 24);
					bitWord = bitWord | (((test ? 1 : 0)) << 25);
					bitWord = bitWord | (((test ? 1 : 0)) << 26);
					bitWord = bitWord | (((test ? 1 : 0)) << 27);
				}
				for (EssREFUstore88K ess : inverters) {
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 28);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 29);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 30);
					bitWord = bitWord
							| (((ess.readValueFromBooleanChannel(REFUStore88KChannelId.GROUND_FAULT) ? 1 : 0)) << 31);
				}
			} catch (Exception e) {
				// TODO: handle exception
			}
			return bitWord;
		});

		b.uint32Supplier(1022, "Technical Unit Energy Throughput YTD", () -> {
			return 0;
		});

		b.uint32Supplier(1024, "Technical Unit Energy Throughput YTD", () -> {
			return 0;
		});

		
		
		
		/*
		 * Battery Level Points
		 */

		for (int i = 1; i < batteries.size(); i++) {
			int batteryPoints = 15;
			BatteryRenaultZoe bms = batteries.get(i);
			
			b.uint32Supplier((1026 + (i-1)*batteryPoints), "Battery-ID (SerialNumber) " + i, () -> {
				int serialNumber = 0;
				try {
					serialNumber = bms.getSerialNumber().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return serialNumber;
			});
			
			b.uint16Supplier((1028 + (i-1)*batteryPoints), "Battery Pack Status" + i, () -> {
				State state = bms.getStateMachineState();
				SystemStatus systemStatus = SystemStatus.UNDEFINED;
					switch (state) {
					case UNDEFINED:
						break;
					case PENDING:
						break;
					case OFF:
						systemStatus = SystemStatus.OFF;
						break;
					case INIT:
						systemStatus = SystemStatus.STARTUP;
						break;
					case RUNNING:
						systemStatus = SystemStatus.ON;
						break;
					case STOPPING:
						break;
					case ERROR:
						systemStatus = SystemStatus.FAULT;
						break;
					case ERRORDELAY:
						systemStatus = SystemStatus.FAULT;
						break;
					case STANDBY:
						systemStatus = SystemStatus.STANDBY;
						break;
					}
				return (short) systemStatus.getValue();
			});
			
			
			b.uint16Supplier((1029 + (i-1)*batteryPoints), "State of Charge Battery " + i, () -> {
				int soc = 0;
				try {
					soc = bms.getSoc().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) soc;
			});
			
			b.uint16Supplier((1030 + (i-1)*batteryPoints), "Daily Energy Throughput Battery " + i, () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1031 + (i-1)*batteryPoints), "Battery Pack " + i + " Voltage", () -> {
				int voltage = 0;
				try {
					voltage = bms.getVoltage().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) voltage;
			});
			
			b.uint16Supplier((1032 + (i-1)*batteryPoints), "Battery Pack " + i + " Cell Voltage-Maximum", () -> {
				int cellVoltageMax = 0;
				try {
					cellVoltageMax = bms.getMaxCellVoltage().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) cellVoltageMax;
			});
			
			b.uint16Supplier((1033 + (i-1)*batteryPoints), "Battery Pack " + i + " Cell Voltage-Minimum", () -> {
				int cellVoltageMin = 0;
				try {
					cellVoltageMin = bms.getMinCellVoltage().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) cellVoltageMin;
			});
			
			b.uint16Supplier((1034 + (i-1)*batteryPoints), "Battery Pack " + i + " Current", () -> {
				int current = 0;
				try {
					current = bms.getCurrent().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) current;
			});
			
			b.uint16Supplier((1035 + (i-1)*batteryPoints), "Battery Pack " + i + " Temperature", () -> {
				int temp = 0;
				try {
					temp = bms.getBatTemp().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) temp;
			});
			
			// Not Available from Battery! Return 0!
			b.uint16Supplier((1036 + (i-1)*batteryPoints), "Battery " + i + " State of Health", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1037 + (i-1)*batteryPoints), "Battery " + i + " Spare A", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1038 + (i-1)*batteryPoints), "Battery " + i + " Spare B", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1039 + (i-1)*batteryPoints), "Battery " + i + " Spare C", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1040 + (i-1)*batteryPoints), "Battery " + i + " Spare D", () -> {
				return (short) 0;
			});
		}

		/*
		 * Inverter Level Points
		 */

		for (int i = 1; i < inverters.size(); i++) {
			int inverterPoints = 12;
			EssREFUstore88K ess = inverters.get(i);
			
			b.uint32Supplier((1206 + (i-1)*inverterPoints), "Inverter-ID (SerialNumber) " + i, () -> {
				int serialNumber = 0;
				try {
					 String string = ess.getSerialNumber();
					 serialNumber = Integer.parseInt(string.trim());
				} catch (Exception e) {
					// TODO: handle exception
				}
				return serialNumber;
			});
			
			b.uint16Supplier((1208 + (i-1)*inverterPoints), "Inverter " + i + " Status", () -> {

				OperatingState operatingState = ess.getOperatingState().value().asEnum();
				SystemStatus systemStatus = SystemStatus.UNDEFINED;
				
				switch (operatingState) {
					case UNDEFINED:
						break;
					case OFF:
						systemStatus = SystemStatus.OFF;
						break;
					case SLEEPING:
						systemStatus = SystemStatus.SLEEP;
						break;
					case STARTING:
						systemStatus = SystemStatus.STARTUP;
						break;
					case MPPT:
						systemStatus = SystemStatus.ON;
						break;
					case THROTTLED:
						systemStatus = SystemStatus.ON;
						break;
					case SHUTTING_DOWN:
						systemStatus = SystemStatus.OFF;
						break;
					case FAULT:
						systemStatus = SystemStatus.FAULT;
						break;
					case STANDBY:
						systemStatus = SystemStatus.STANDBY;
						break;
					case STARTED:
						systemStatus = SystemStatus.SLEEP;
						break;
					}
				return (short) systemStatus.getValue();
			});
			
			b.uint16Supplier((1209 + (i-1)*inverterPoints), "Inverter " + i + " DC Voltage", () -> {
				int dcVoltage = 0;
				try {
					dcVoltage = ess.getDcVoltage().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) dcVoltage;
			});
			
			b.uint16Supplier((1210 + (i-1)*inverterPoints), "Inverter " + i + " AC Voltage", () -> {
				int acVoltage = 0;
				try {
					acVoltage = ess.getAcVoltage().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) acVoltage;
			});
			
			b.uint16Supplier((1211 + (i-1)*inverterPoints), "Inverter " + i + " AC Current", () -> {
				int acCurrent = 0;
				try {
					acCurrent = ess.getAcCurrent().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) acCurrent;
			});
			
			b.uint16Supplier((1212 + (i-1)*inverterPoints), "Inverter " + i + " Active Power", () -> {
				int activePower = 0;
				try {
					activePower = ess.getActivePower().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) activePower;
			});
			
			b.uint16Supplier((1213 + (i-1)*inverterPoints), "Inverter " + i + " Reactive Power", () -> {
				int reactivePower = 0;
				try {
					reactivePower = ess.getReactivePower().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) reactivePower;
			});
			
			b.uint16Supplier((1214 + (i-1)*inverterPoints), "Inverter " + i + " Apparent Power", () -> {
				int apparentPower = 0;
				try {
					apparentPower = ess.getReactivePower().value().get();
				} catch (Exception e) {
					// TODO: handle exception
				}
				return (short) apparentPower;
			});
			
			b.uint16Supplier((1215 + (i-1)*inverterPoints), "Inverter " + i + " Spare A", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1216 + (i-1)*inverterPoints), "Inverter " + i + " Spare B", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1217 + (i-1)*inverterPoints), "Inverter " + i + " Spare C", () -> {
				return (short) 0;
			});
			
			b.uint16Supplier((1218 + (i-1)*inverterPoints), "Inverter " + i + " Spare D", () -> {
				return (short) 0;
			});
			
			
		}
		
		
		


		/*************
		 * TMH-to-ESS
		 * 
		 * Implemented as "holding registers" with address offset 0. See
		 * https://en.wikipedia.org/wiki/Modbus#Modbus_object_types
		 *************/

		/*
		 * Technical Unit Level Points
		 */
		
		/**
		 * Beispiel
		 */
//				.uint16Consumer(0,
//						"System Status: The system state command from TMH to Technical Unit- reference table below",
//						(value) -> {
//							System.out.println("Trying to write [" + value + "] to System Status");
//						}); //
//
//		
//				for (Battery bat : batteries) {
//					b.float64(0, "asdf", bat.getCurrent().value().orElse(0));
//				}	
		
				b.uint16Consumer(0,"System Status: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to System Status");
				}); //
		
				b.uint32Consumer(1,"Power Request Active Power: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to Active Power");
					ManagedSymmetricEss ess = componentManager.getComponent("ess0");
					ess.getSetActivePowerEquals().setNextValue(value);
				}); //
				
				b.uint32Consumer(3,"Power Request Reactive Power: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to Reactive Power");
					ManagedSymmetricEss ess = componentManager.getComponent("ess0");
					ess.getSetReactivePowerEquals().setNextValue(value);
				}); //
				
				b.uint16Consumer(5,"Error Reset: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to Error Reset");
				}); //
				
				b.uint16Consumer(6,"Battery Error Data Request: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to Error Reset");
				}); //
				
				b.uint16Consumer(7,"Alive Counter: ",(value) -> {
					System.out.println("Trying to write [" + value + "] to Alive Counter");
				}); //
				
				
				for (Battery bat : batteries) {
					b.float64(0, "asdf", bat.getCurrent().value().orElse(0));
				}	
		
		/*
		 * Inverter Level Points (Just for testing purposes!)
		 */
				
				
				for (int i = 1; i < inverters.size(); i++) {
					int inverterPoints = 5;
					EssREFUstore88K ess = inverters.get(i);
					
					b.uint16Consumer((8 +(i-1)*inverterPoints),"Inverter Status " + i + ":",(value) -> {
						System.out.println("Trying to write [" + value + "] to System Status");
						ess.getSetActivePowerEquals().setNextValue(value);
					}); //
					
					b.uint32Consumer((9 +(i-1)*inverterPoints),"Power Request Active Power " + i + ":",(value) -> {
						System.out.println("Trying to write [" + value + "] to System Status");
						ess.getSetActivePowerEquals().setNextValue(value);
					}); //
					
					b.uint32Consumer((12 +(i-1)*inverterPoints),"Power Request Rective Power " + i + ":",(value) -> {
						System.out.println("Trying to write [" + value + "] to System Status");
						ess.getSetActivePowerEquals().setNextValue(value);
					}); //
				}

				return b.build();
	}
}
