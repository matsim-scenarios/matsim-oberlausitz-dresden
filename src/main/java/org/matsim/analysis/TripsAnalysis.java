package org.matsim.analysis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.geotools.api.feature.simple.SimpleFeature;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.options.ShpOptions;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Trip analysis.
 */
public class TripsAnalysis {
	private static final String CONFIG_PATH = "input/v2025.0/oberlausitz-dresden-v2025.0-10pct.config.xml";
	private static final String NETWORK_FILE = "/Users/luchengqi/Documents/matsim-scenarios/oberlausitz-dresden/oberlausitz-dresden-v2025.0-network-with-pt-and-uam.xml.gz";
	private static final String TRANSIT_SCHEDULE_FILE = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/oberlausitz-dresden/oberlausitz-dresden-v2025.0/input/oberlausitz-dresden-v2025.0-transitSchedule.xml.gz";
	private static final String TRANSIT_VEHICLES_FILE = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/oberlausitz-dresden/oberlausitz-dresden-v2025.0/input/oberlausitz-dresden-v2025.0-transitVehicles.xml.gz";
	private static final String SERVICE_AREA_SHP = "/Users/luchengqi/Documents/matsim-scenarios/oberlausitz-dresden/shp/uam-service-area.shp";
	private static final String PLANS_FILE = "/Users/luchengqi/Documents/matsim-scenarios/oberlausitz-dresden/014.output_plans-cleaned.xml.gz";
	private static final String OUTPUT_CSV = "/Users/luchengqi/Documents/matsim-scenarios/oberlausitz-dresden/potential-uam-trips.csv";

	private static final String[] CSV_HEADER = {
		// person attributes
		"person_id", "age", "sex", "hh_income_group", "hh_size", "income", "car_avail", "pt_subscription",
		// trip attributes
		"departure_time", "from_x", "from_y", "to_x", "to_y", "trip_purpose",
		"car_total_travel_time", "pt_total_travel_time", "uam_total_travel_time", "selected_mode"
	};

	public static void main(String[] args) throws IOException {
		PtTravelTimeEstimator ptTravelTimeEstimator = PtTravelTimeEstimator.create(CONFIG_PATH, NETWORK_FILE,
			TRANSIT_SCHEDULE_FILE, TRANSIT_VEHICLES_FILE);

		// the network of the pt scenario is reused for the car and the uam estimator
		Config config = ptTravelTimeEstimator.getScenario().getConfig();
		Network network = ptTravelTimeEstimator.getScenario().getNetwork();

		CarTravelTimeEstimator carTravelTimeEstimator = new CarTravelTimeEstimator(network, config);

		// adding the flying links and drt as an allowed mode on the ground links within the service area
//		UamInfrastructure.prepareDresdenOberlaustizUamNetwork(network, SERVICE_AREA_SHP);
		UamTravelTimeEstimator uamTravelTimeEstimator = new UamTravelTimeEstimator(network, config);

		Population outputplans = PopulationUtils.readPopulation(PLANS_FILE);
		ShpOptions shp = new ShpOptions(SERVICE_AREA_SHP, "EPSG:25832", null);

		List<SimpleFeature> features = shp.readFeatures();

		RoutingModeMainModeIdentifier mainModeIdentifier = new RoutingModeMainModeIdentifier();

		try (BufferedWriter writer = IOUtils.getBufferedWriter(OUTPUT_CSV);
			 CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(CSV_HEADER).build())) {

			for (Person person : outputplans.getPersons().values()) {
				if (!"person".equals(person.getAttributes().getAttribute("subpopulation").toString())) {
					// this is not a normal person, skip
					continue;
				}

				Plan plan = person.getSelectedPlan();
				List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
				for (TripStructureUtils.Trip trip : trips) {
					double departureTIme = trip.getOriginActivity().getEndTime().orElse(86401.);
					if (departureTIme > 86400) {
						// outside the analyzation time window
						continue;
					}

					Coord fromCoord = trip.getOriginActivity().getCoord();
					Coord toCoord = trip.getDestinationActivity().getCoord();

					Point fromPoint = MGC.coord2Point(fromCoord);
					Point toPoint = MGC.coord2Point(toCoord);

					SimpleFeature fromArea = identifyFeature(features, fromPoint);
					if (fromArea == null) {
						// the trip does not originate from the UAM service area
						continue;
					}

					SimpleFeature toArea = identifyFeature(features, toPoint);
					if (toArea == null) {
						// the trip does not end in the UAM service area
						continue;
					}

					if (fromArea.getAttribute("id").toString().equals(toArea.getAttribute("id").toString())) {
						// the trip only travels within the same bubble, not allowed
						continue;
					}

					// if we reach here, we find a potential UAM trip
					PtTravelTimeEstimator.PtTravelTime ptTravelTime = ptTravelTimeEstimator.estimate(
						trip.getOriginActivity(), trip.getDestinationActivity(), departureTIme);
					CarTravelTimeEstimator.CarTravelTime carTravelTime = carTravelTimeEstimator.estimate(
						trip.getOriginActivity(), trip.getDestinationActivity(), departureTIme);
					UamTravelTimeEstimator.UamTravelTime uamTravelTime = uamTravelTimeEstimator.estimate(
						trip.getOriginActivity(), trip.getDestinationActivity(), departureTIme);

					csvPrinter.printRecord(
						// person attributes
						person.getId(),
						person.getAttributes().getAttribute("age"),
						person.getAttributes().getAttribute("sex"),
						person.getAttributes().getAttribute("hhIncome"),
						person.getAttributes().getAttribute("hhSize"),
						person.getAttributes().getAttribute("income"),
						person.getAttributes().getAttribute("carAvail"),
						person.getAttributes().getAttribute("ptTicket"),
						// trip attributes
						departureTIme,
						fromCoord.getX(),
						fromCoord.getY(),
						toCoord.getX(),
						toCoord.getY(),
						removeTimeFromActivityType(trip.getDestinationActivity().getType()),
						// no connection found -> empty cell
						carTravelTime == null ? "" : carTravelTime.totalTravelTime(),
						ptTravelTime == null ? "" : ptTravelTime.totalTravelTime(),
						uamTravelTime == null ? "" : uamTravelTime.totalTravelTime(),
						mainModeIdentifier.identifyMainMode(trip.getTripElements())
					);
				}
			}
		}
	}

	/**
	 * The activity types of the scenario carry the typical duration (e.g. "leisure_3600"), which is not of interest
	 * for the trip purpose.
	 */
	private static String removeTimeFromActivityType(String activityType) {
		return activityType.replaceAll("_[0-9]+$", "");
	}

	private static SimpleFeature identifyFeature(
		Collection<SimpleFeature> features,
		Point point) {

		for (SimpleFeature feature : features) {
			Geometry geometry = (Geometry) feature.getDefaultGeometry();

			if (geometry.contains(point)) {
				return feature;
			}
		}

		return null;
	}


}
