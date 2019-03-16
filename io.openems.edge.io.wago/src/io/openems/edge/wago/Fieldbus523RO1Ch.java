package io.openems.edge.wago;

import java.util.concurrent.atomic.AtomicInteger;

import io.openems.edge.bridge.modbus.api.element.AbstractModbusElement;
import io.openems.edge.bridge.modbus.api.element.DummyCoilElement;
import io.openems.edge.common.channel.internal.BooleanReadChannel;
import io.openems.edge.common.channel.internal.BooleanWriteChannel;

public class Fieldbus523RO1Ch extends FieldbusModule {

	private final static AtomicInteger count = new AtomicInteger(0);
	private final static String ID_TEMPLATE = "RELAY_M";

	private final AbstractModbusElement<?>[] inputElements;
	private final AbstractModbusElement<?>[] outputElements;
	private final BooleanReadChannel[] readChannels;

	public Fieldbus523RO1Ch(Wago parent, int inputOffset, int outputOffset) {
		String id = ID_TEMPLATE + count.incrementAndGet();

		BooleanWriteChannel channel1 = new BooleanWriteChannel(parent, new FieldbusChannel(id));
		BooleanReadChannel channel2 = new BooleanReadChannel(parent, new FieldbusChannel(id + "_HAND"));
		this.readChannels = new BooleanReadChannel[] { channel1, channel2 };

		parent.addChannel(channel1);
		parent.addChannel(channel2);

		this.inputElements = new AbstractModbusElement<?>[] { //
				parent.createModbusElement(channel1.channelId(), outputOffset), //
				new DummyCoilElement(outputOffset + 1), //
				parent.createModbusElement(channel2.channelId(), inputOffset), //
				new DummyCoilElement(inputOffset + 1) //
		};

		this.outputElements = new AbstractModbusElement<?>[] { //
				parent.createModbusElement(channel1.channelId(), outputOffset), //
				new DummyCoilElement(outputOffset + 1) //
		};
	}

	@Override
	public String getName() {
		return "WAGO I/O 750-523 1-channel relay output module";
	}

	@Override
	public AbstractModbusElement<?>[] getInputElements() {
		return this.inputElements;
	}

	@Override
	public AbstractModbusElement<?>[] getOutputElements() {
		return this.outputElements;
	}

	@Override
	public int getOutputCoils() {
		return 2;
	}

	@Override
	public int getInputCoils() {
		return 2;
	}

	@Override
	public BooleanReadChannel[] getChannels() {
		return this.readChannels;
	}
}
