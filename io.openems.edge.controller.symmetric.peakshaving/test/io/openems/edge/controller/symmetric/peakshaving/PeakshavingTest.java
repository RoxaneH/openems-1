package io.openems.edge.controller.symmetric.peakshaving;

import org.junit.Test;

import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.controller.test.ControllerTest;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.test.DummyManagedSymmetricEss;
import io.openems.edge.ess.test.DummyPower;
import io.openems.edge.meter.api.SymmetricMeter;
import io.openems.edge.meter.test.DummySymmetricMeter;

public class PeakshavingTest {

	@Test
	public void test() throws Exception {
		// Initialize Controller
		PeakShaving controller = new PeakShaving();
		// Add referenced services
		DummyComponentManager componentManager = new DummyComponentManager();
		controller.componentManager = componentManager;
		DummyPower power = new DummyPower(0.3, 0.3, 0.1);
		controller.power = power;
		// Activate (twice, so that reference target is set)
		MyConfig config = new MyConfig("ctrl0", "ess0", "meter0", 100000, 50000);
		controller.activate(null, config);
		controller.activate(null, config);
		// Prepare Channels
		ChannelAddress ess = new ChannelAddress("ess0", "ActivePower");
		ChannelAddress grid = new ChannelAddress("meter0", "ActivePower");
		ChannelAddress essSetPower = new ChannelAddress("ess0", "SetActivePowerEquals");
		// Build and run test
		ManagedSymmetricEss essComponent = new DummyManagedSymmetricEss("ess0", power);
		SymmetricMeter meterComponent = new DummySymmetricMeter("meter0");
		new ControllerTest(controller, componentManager, essComponent, meterComponent) //
				.next(new TestCase() //
						.input(ess, 0).input(grid, 120000) //
						.output(essSetPower, 6000)) //
				.next(new TestCase() //
						.input(ess, 0).input(grid, 120000) //
						.output(essSetPower, 12000)) //
				.next(new TestCase() //
						.input(ess, 3793).input(grid, 120000 - 3793) //
						.output(essSetPower, 16483)) //
				.next(new TestCase() //
						.input(ess, 8981).input(grid, 120000 - 8981) //
						.output(essSetPower, 19649)) //
				.next(new TestCase() //
						.input(ess, 13723).input(grid, 120000 - 13723) //
						.output(essSetPower, 21577)) //
				.next(new TestCase() //
						.input(ess, 17469).input(grid, 120000 - 17469) //
						.output(essSetPower, 22436)) //
				.next(new TestCase() //
						.input(ess, 20066).input(grid, 120000 - 20066) //
						.output(essSetPower, 22531)) //
				.next(new TestCase() //
						.input(ess, 21564).input(grid, 120000 - 21564) //
						.output(essSetPower, 22171)) //
				.next(new TestCase() //
						.input(ess, 22175).input(grid, 120000 - 22175) //
						.output(essSetPower, 21608)) //
				.next(new TestCase() //
						.input(ess, 22173).input(grid, 120000 - 22173) //
						.output(essSetPower, 21017)) //
				.next(new TestCase() //
						.input(ess, 21816).input(grid, 120000 - 21816) //
						.output(essSetPower, 20508)) //
				.next(new TestCase() //
						.input(ess, 21311).input(grid, 120000 - 21311) //
						.output(essSetPower, 20129)) //
				.next(new TestCase() //
						.input(ess, 20803).input(grid, 120000 - 20803) //
						.output(essSetPower, 19889)) //
				.next(new TestCase() //
						.input(ess, 20377).input(grid, 120000 - 20377) //
						.output(essSetPower, 19767)) //
				.run();
	}

}
