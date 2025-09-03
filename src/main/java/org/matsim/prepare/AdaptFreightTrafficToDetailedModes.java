package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;


/**
 *  This class should not be needed anymore, because the subpopulations and the modes are set in the generation classes of the long-distance freight traffic and the small-scale commercial traffic.
 *  I think it can be removed: RE:09/25
 */
@Deprecated
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
	public Integer call() {

		Population population = PopulationUtils.readPopulation(input.toString());
		log.error("This class can not be used in the current implementaion. If It is needed, please update the class. RE 09/25");
//		for (Person person : population.getPersons().values()) {
//			if (PopulationUtils.getSubpopulation(person).equals(FREIGHT)) {
//				for (Plan plan : person.getPlans()) {
//					for (Leg leg : TripStructureUtils.getLegs(plan)) {
//						if (!leg.getMode().equals(FREIGHT)) {
//							leg.setMode(FREIGHT);
//						}
//					}
//				}
//			}
////			yy potentially add adaption of smallScaleCommercialTraffic here, see same class in matsim-lausitz
//		}
		PopulationUtils.writePopulation(population, output.toString());
		return 0;
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
