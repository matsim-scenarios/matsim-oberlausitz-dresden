package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.matsim.run.OberlausitzDresdenScenario.FREIGHT;

@CommandLine.Command(
	name = "adapt-freight-plans",
	description = "Adapt all freight plans (including small scall commercial traffic) to new standards."
)
public class AdaptFreightTrafficToDetailedModes implements MATSimAppCommand {

	Logger log = LogManager.getLogger(AdaptFreightTrafficToDetailedModes.class);

	@CommandLine.Parameters(arity = "1", paramLabel = "INPUT", description = "Path to input population")
	private Path input;

	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private Path output;

	public static void main(String[] args) {
		new AdaptFreightTrafficToDetailedModes().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Population population = PopulationUtils.readPopulation(input.toString());

		for (Person person : population.getPersons().values()) {
			if (PopulationUtils.getSubpopulation(person).equals("freight")) {
				adaptFreightPerson(person);
			} else if (PopulationUtils.getSubpopulation(person).equals(FREIGHT)) {
				for (Plan plan : person.getPlans()) {
					for (Leg leg : TripStructureUtils.getLegs(plan)) {
						if (!leg.getMode().equals(FREIGHT)) {
							leg.setMode(FREIGHT);
						}
					}
				}
			}
//			yy potentially add adaption of smallScaleCommercialTraffic here, see same class in matsim-lausitz
		}
		PopulationUtils.writePopulation(population, output.toString());
		return 0;
	}

	private static void adaptFreightPerson(Person person) {
		//			rename freight subpop to longDistanceFreight
		person.getAttributes().removeAttribute("subpopulation");
		person.getAttributes().putAttribute("subpopulation", FREIGHT);

//				rename each leg mode freight to longDistanceFreight
		for (Plan plan : person.getPlans()) {
			for (Leg leg : TripStructureUtils.getLegs(plan)) {
				if (leg.getMode().equals("freight")) {
					leg.setMode(FREIGHT);
				}
			}
		}
	}

	private static @NotNull Population removeSmallScaleCommercialTrafficFromPopulation(Population population) {
		Population newPop = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		for (Person person : population.getPersons().values()) {
			if (PopulationUtils.getSubpopulation(person).contains("commercialPersonTraffic")
			|| PopulationUtils.getSubpopulation(person).contains("goodsTraffic")) {
//				do not add commercial or goods traffic from RE
				continue;
			}
			newPop.addPerson(person);
		}
		return newPop;
	}
}
