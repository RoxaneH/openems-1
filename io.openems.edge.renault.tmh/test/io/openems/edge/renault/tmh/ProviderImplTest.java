package io.openems.edge.renault.tmh;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import io.openems.edge.renault.tmh.RenaultTmh;

/*
 * Example JUNit test case
 *
 */

public class ProviderImplTest {

	/*
	 * Example test method
	 */

	@Test
	public void simple() {
		RenaultTmh impl = new RenaultTmh();
		assertNotNull(impl);
	}

}