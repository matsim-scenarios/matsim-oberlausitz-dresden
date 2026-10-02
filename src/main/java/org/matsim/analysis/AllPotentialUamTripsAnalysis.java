package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

public class AllPotentialUamTripsAnalysis {

	public static void main(String[] args) throws IOException {
		String inputPlans = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/oberlausitz-dresden/oberlausitz-dresden-v2025.0/input/oberlausitz-dresden-v2025.0-10pct.plans-initial.xml.gz";
		double minDistance = 15000.;
		String shpPath = "input/shp/oberlausitz.shp";
		String output = "/Users/luchengqi/Documents/matsim-scenarios/oberlausitz-dresden/analysis/all-potential-uam-trips.csv";

		String[] header = {
			// person attributes
			"person_id", "age", "sex", "hh_income_group", "hh_size", "income", "car_avail", "pt_subscription",
			// trip attributes
			"departure_time", "from_x", "from_y", "to_x", "to_y", "trip_purpose"
		};

		Population plans = PopulationUtils.readPopulation(inputPlans);
		ShpOptions shp = new ShpOptions(shpPath, "EPSG:25832", null);
		Geometry serviceArea = shp.getGeometry();

		BufferedWriter writer = IOUtils.getBufferedWriter(output);
		CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(header).build());

		for (Person person : plans.getPersons().values()) {
			if ("person".equals(person.getAttributes().getAttribute("subpopulation"))) {
				Plan plan = person.getSelectedPlan();
				List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
				for (TripStructureUtils.Trip trip : trips) {
					Coord fromCoord = trip.getOriginActivity().getCoord();
					Coord toCoord = trip.getDestinationActivity().getCoord();

					if (MGC.coord2Point(fromCoord).within(serviceArea) && MGC.coord2Point(toCoord).within(serviceArea)) {
						double distance = CoordUtils.calcEuclideanDistance(fromCoord, toCoord);
						if (distance > minDistance) {
							double departureTime = trip.getOriginActivity().getEndTime().orElse(-1);
							if (departureTime < 0) {
								continue;
							}
							departureTime = departureTime > 86400 ? departureTime - 86400 : departureTime;

							// write down
							csvPrinter.printRecord(
								// person attributes
								person.getId().toString(),
								person.getAttributes().getAttribute("age"),
								person.getAttributes().getAttribute("sex"),
								person.getAttributes().getAttribute("hhIncome"),
								person.getAttributes().getAttribute("hhSize"),
								person.getAttributes().getAttribute("income"),
								person.getAttributes().getAttribute("carAvail"),
								person.getAttributes().getAttribute("ptTicket"),
								// trip attributes
								Double.toString(departureTime),
								fromCoord.getX(),
								fromCoord.getY(),
								toCoord.getX(),
								toCoord.getY(),
								removeTimeFromActivityType(trip.getDestinationActivity().getType())
							);
						}
					}
				}


			}
		}
		csvPrinter.close();
	}


	/**
	 * The activity types of the scenario carry the typical duration (e.g. "leisure_3600"), which is not of interest
	 * for the trip purpose.
	 */
	private static String removeTimeFromActivityType(String activityType) {
		return activityType.replaceAll("_[0-9]+$", "");
	}
}
