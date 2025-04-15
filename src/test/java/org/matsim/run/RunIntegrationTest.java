package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.matsim.application.MATSimApplication;


public class RunIntegrationTest {

	@Test
	public void runScenario() {

		assert MATSimApplication.execute(OberlausitzDresdenScenario.class,
				"--1pct",
				"--iterations", "1") == 0 : "Must return non error code";

	}
}
