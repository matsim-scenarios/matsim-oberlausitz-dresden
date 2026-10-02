package org.matsim.run;

import org.apache.commons.lang3.tuple.Pair;
import org.matsim.analysis.personMoney.PersonMoneyEventsAnalysisModule;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimApplication;
import org.matsim.application.analysis.CheckPopulation;
import org.matsim.application.analysis.traffic.LinkStats;
import org.matsim.application.options.SampleOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.application.prepare.CreateLandUseShp;
import org.matsim.application.prepare.counts.CreateCountsFromBAStData;
import org.matsim.application.prepare.freight.tripExtraction.ExtractRelevantFreightTrips;
import org.matsim.application.prepare.network.CleanNetwork;
import org.matsim.application.prepare.network.CreateNetworkFromSumo;
import org.matsim.application.prepare.population.*;
import org.matsim.application.prepare.pt.CreateTransitScheduleFromGtfs;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.distribution.NormalDistributionGenerator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.waiting_time_estimation.ConstantWaitingTimeEstimator;
import org.matsim.contrib.drt.fare.DrtFareParams;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.passenger.PassengerRequestValidator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.pt.fare.PtFareModule;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.core.replanning.annealing.ReplanningAnnealerConfigGroup;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.estimator.ShpBasedDrtRequestValidator;
import org.matsim.estimator.UamInfrastructure;
import org.matsim.prepare.AdaptFreightTrafficToDetailedModes;
import org.matsim.prepare.PrepareNetwork;
import org.matsim.prepare.PreparePopulation;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;
import playground.vsp.scoring.IncomeDependentUtilityOfMoneyPersonScoringParameters;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@CommandLine.Command(header = ":: OberlausitzDresden Scenario ::", version = OberlausitzDresdenScenario.VERSION, mixinStandardHelpOptions = true)
@MATSimApplication.Prepare({
	CreateNetworkFromSumo.class, CreateTransitScheduleFromGtfs.class, TrajectoryToPlans.class, GenerateShortDistanceTrips.class,
	MergePopulations.class, ExtractRelevantFreightTrips.class, DownSamplePopulation.class, ExtractHomeCoordinates.class,
	CreateLandUseShp.class, ResolveGridCoordinates.class, FixSubtourModes.class, AdjustActivityToLinkDistances.class, XYToLinks.class,
	CleanNetwork.class, PrepareNetwork.class, SplitActivityTypesDuration.class, PreparePopulation.class, CreateCountsFromBAStData.class,
	AdaptFreightTrafficToDetailedModes.class
})
@MATSimApplication.Analysis({
	LinkStats.class, CheckPopulation.class
})
public class OberlausitzDresdenScenario extends MATSimApplication {
	private static final String UAM_MODE = "uam";
	static final String VERSION = "v2025.0";

	public static final String FREIGHT = "longDistanceFreight";

	@CommandLine.Mixin
	private final SampleOptions sample = new SampleOptions(100, 25, 10, 1);

	@CommandLine.Option(names = "--uam", description = "Enable UAM network, routing, scoring and QSim modules.")
	private boolean uamEnabled = false;

	@CommandLine.Option(names = "--asc", description = "asc for the uam", defaultValue = "0.")
	protected static double asc;

	@CommandLine.Option(names = "--typ-wt", description = "typical waiting time (base)", defaultValue = "600")
	protected double typicalWaitTime;

	@CommandLine.Option(names = "--service-area", description = "ground coverage area of uam", defaultValue = "/Users/luchengqi/Documents/MATSimScenarios/Oberlausitz-Dresden/shp/uam-service-area.shp")
	protected static String serviceArea;

	@CommandLine.Option(names = "--base-fare", description = "base fare of the uam trips", defaultValue = "0")
	protected static double baseFare;

	@CommandLine.Option(names = "--dist-fare", description = "distance fare per meter", defaultValue = "0.0003")
	protected static double distanceFare;

	public OberlausitzDresdenScenario(@Nullable Config config) {
		super(config);
	}

	public OberlausitzDresdenScenario() {
		super(String.format("input/%s/oberlausitz-dresden-%s-10pct.config.xml", VERSION, VERSION));
	}

	public static void main(String[] args) {
		MATSimApplication.run(OberlausitzDresdenScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {

		// Add all activity types with time bins
		SnzActivities.addScoringParams(config);

		//		add simwrapper config module
		SimWrapperConfigGroup simWrapper = ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class);

		simWrapper.defaultParams().context = "";
		simWrapper.defaultParams().mapCenter = "14.5,51.53";
		simWrapper.defaultParams().mapZoomLevel = 6.8;
		simWrapper.defaultParams().shp = "./shp/oberlausitz.shp";

		if (sample.isSet()) {
			config.controller().setOutputDirectory(sample.adjustName(config.controller().getOutputDirectory()));
			config.plans().setInputFile(sample.adjustName(config.plans().getInputFile()));
			config.controller().setRunId(sample.adjustName(config.controller().getRunId()));

			config.qsim().setFlowCapFactor(sample.getSample());
			config.qsim().setStorageCapFactor(sample.getSample());
			config.counts().setCountsScaleFactor(sample.getSample());
			simWrapper.sampleSize = sample.getSample();
		}

		config.plans().setActivityDurationInterpretation(PlansConfigGroup.ActivityDurationInterpretation.tryEndTimeThenDuration);

		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.abort);

		//		performing set to 6.0 after calibration task force july 24
		double performing = 6.0;
		ScoringConfigGroup scoringConfigGroup = config.scoring();
		scoringConfigGroup.setPerforming_utils_hr(performing);
		scoringConfigGroup.setWriteExperiencedPlans(true);
		scoringConfigGroup.setPathSizeLogitBeta(0.);
		scoringConfigGroup.addActivityParams(new ScoringConfigGroup.ActivityParams("freight_start").setTypicalDuration(30 * 60.));
		scoringConfigGroup.addActivityParams(new ScoringConfigGroup.ActivityParams("freight_end").setTypicalDuration(30 * 60.));

//		set ride scoring params dependent from car params
//		2.0 + 1.0 = alpha + 1
//		ride cost = alpha * car cost
//		ride marg utility of traveling = (alpha + 1) * marg utility travelling car + alpha * beta perf
		double alpha = 2;
		RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(scoringConfigGroup, alpha);

		config.qsim().setUsingTravelTimeCheckInTeleportation(true);
		config.qsim().setUsePersonIdForMissingVehicleId(false);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

//		configure annealing params
		config.replanningAnnealer().setActivateAnnealingModule(true);
		ReplanningAnnealerConfigGroup.AnnealingVariable annealingVar = new ReplanningAnnealerConfigGroup.AnnealingVariable();
		annealingVar.setAnnealType(ReplanningAnnealerConfigGroup.AnnealOption.sigmoid);
		annealingVar.setEndValue(0.01);
		annealingVar.setHalfLife(0.5);
		annealingVar.setShapeFactor(0.01);
		annealingVar.setStartValue(0.45);
		annealingVar.setDefaultSubpopulation("person");
		config.replanningAnnealer().addAnnealingVariable(annealingVar);

		//		set pt fare calc model to fareZoneBased = fare of vvo tarifzonen are paid for trips within fare zones
//		every other trip: Deutschlandtarif
//		for more info see PTFareModule / ChainedPtFareCalculator classes in vsp contrib
		PtFareConfigGroup ptFareConfigGroup = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);

		FareZoneBasedPtFareParams vvo = new FareZoneBasedPtFareParams();
		vvo.setTransactionPartner("VVO Tarifzone 10 Dresden");
		vvo.setDescription("VVO Tarifzone 10 Dresden");
		vvo.setOrder(1);
		vvo.setFareZoneShp("../shp/vvo_tarifzone_10_dresden_utm32n.shp");

		DistanceBasedPtFareParams germany = DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024;
		germany.setTransactionPartner("Deutschlandtarif");
		germany.setDescription("Deutschlandtarif");
		germany.setOrder(2);

		ptFareConfigGroup.addParameterSet(vvo);
		ptFareConfigGroup.addParameterSet(germany);

//		TODO: emissions config
		// prepare uam config
		if (uamEnabled) {
			configureUamImitatedByDrt(config);
		}

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		//		add longDistanceFreight as allowed modes together with car
		PrepareNetwork.prepareFreightNetwork(scenario.getNetwork());

		for (Link link : scenario.getNetwork().getLinks().values()) {
			DisallowedNextLinks disallowed = NetworkUtils.getDisallowedNextLinks(link);
			if (disallowed != null) {
				link.getAllowedModes().forEach(disallowed::removeDisallowedLinkSequences);
				if (disallowed.isEmpty()) {
					NetworkUtils.removeDisallowedNextLinks(link);
				}
			}
		}

		// prepare uam scenario
		if (uamEnabled) {
			scenario.getPopulation()
				.getFactory()
				.getRouteFactories()
				.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

			// prepare uam network
			prepareUamNetwork(scenario);
			// prepare uam vehicles
			prepareUamVehicles(scenario);
		}

	}

	@Override
	protected void prepareControler(Controler controler) {
		//analyse PersonMoneyEvents
		controler.addOverridingModule(new PersonMoneyEventsAnalysisModule());
		controler.addOverridingModule(new SimWrapperModule());
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new PtFareModule());
				bind(ScoringParametersForPerson.class).to(IncomeDependentUtilityOfMoneyPersonScoringParameters.class).asEagerSingleton();

				addTravelTimeBinding(TransportMode.ride).to(carTravelTime());
				addTravelDisutilityFactoryBinding(TransportMode.ride).to(carTravelDisutilityFactoryKey());
			}
		});

		// prepare controler for uam
		if (uamEnabled) {
			// binding DVRP and DRT related modules
			controler.addOverridingModule(new DvrpModule());
			controler.addOverridingModule(new MultiModeDrtModule());
			controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(ConfigUtils.addOrGetModule(controler.getConfig(), MultiModeDrtConfigGroup.class)));

			// binding DRT estimator
			Config config = controler.getConfig();
			MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
			for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
				controler.addOverridingModule(new AbstractModule() {
					@Override
					public void install() {
						DrtEstimatorModule.bindEstimator(binder(), drtConfigGroup.mode).toInstance(
							new DirectTripBasedDrtEstimator.Builder()
								.setWaitingTimeEstimator(new ConstantWaitingTimeEstimator(typicalWaitTime))
								.setWaitingTimeDistributionGenerator(new NormalDistributionGenerator(1, 0.))
								.setRideDurationEstimator(new ConstantRideDurationEstimator(1.0, 120))
								.setRideDurationDistributionGenerator(new NormalDistributionGenerator(2, 0.))
								.build()
						);
					}
				});

				// Overwrite the passenger request validator with the ShpBasedDrtRequestValidator
				controler.addOverridingQSimModule(new AbstractDvrpModeQSimModule(drtConfigGroup.mode) {
					@Override
					protected void configureQSim() {
						bindModal(PassengerRequestValidator.class).toProvider(
							modalProvider(getter -> new ShpBasedDrtRequestValidator
								(new ShpOptions(serviceArea, "EPSG:25832", null)))).asEagerSingleton();
					}
				});
			}
		}
	}


	private static void configureUamImitatedByDrt(Config config) {
		// Necessary DVRP configurations
		DvrpConfigGroup dvrpConfigGroup = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
		dvrpConfigGroup.networkModes = Set.of(TransportMode.drt);
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);

		// DRT config for the "uam"
		MultiModeDrtConfigGroup multiModeDrtConfigGroup = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
		if (multiModeDrtConfigGroup.getModalElements().isEmpty()) {
			DrtConfigGroup drtConfigGroup = new DrtConfigGroup();
			drtConfigGroup.mode = UAM_MODE;
			drtConfigGroup.operationalScheme = DrtConfigGroup.OperationalScheme.door2door;
//			drtConfigGroup.transitStopFile = "drt-stops.xml"; // TODO
//			drtConfigGroup.drtServiceAreaShapeFile = "service_area_shp.shp"; // TODO
			drtConfigGroup.stopDuration = 60.;

			// set uam fare
			DrtFareParams drtFareParams = new DrtFareParams();
			drtFareParams.baseFare = baseFare;
			drtFareParams.distanceFare_m = distanceFare;
			drtConfigGroup.addParameterSet(drtFareParams);

//			optimization params now are in its own paramSet, hence the below lines
			DrtOptimizationConstraintsParams optimizationConstraints = new DrtOptimizationConstraintsParams();
			DefaultDrtOptimizationConstraintsSet optimizationConstraintsSet = new DefaultDrtOptimizationConstraintsSet();
			optimizationConstraintsSet.maxWaitTime = 900;
			optimizationConstraintsSet.maxTravelTimeBeta = 1200.;
			optimizationConstraintsSet.maxTravelTimeAlpha = 1.5;
			optimizationConstraints.addParameterSet(optimizationConstraintsSet);
			optimizationConstraintsSet.maxWalkDistance = 1000;
			drtConfigGroup.addParameterSet(optimizationConstraints);
			drtConfigGroup.addParameterSet(new ExtensiveInsertionSearchParams());
			multiModeDrtConfigGroup.addParameterSet(drtConfigGroup);
		}

		// set to drt estimate and teleport
		for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
			drtConfigGroup.simulationType = DrtConfigGroup.SimulationType.estimateAndTeleport;
		}

		// scoring for DRT
		ScoringConfigGroup scoringConfigGroup = ConfigUtils.addOrGetModule(config, ScoringConfigGroup.class);
		if (!scoringConfigGroup.getModes().containsKey(UAM_MODE)) {
			scoringConfigGroup.addModeParams(new ScoringConfigGroup.ModeParams(UAM_MODE)
				.setConstant(asc)
				.setMarginalUtilityOfTraveling(-0.));
		}
		DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfigGroup, config.scoring(), config.routing());

		//	add uam to mode choice
		List<String> modes = new ArrayList<>(List.of(config.subtourModeChoice().getModes()));
		modes.add(UAM_MODE);
		config.subtourModeChoice().setModes(modes.toArray(new String[0]));
	}

	private static void prepareUamNetwork(Scenario scenario) {
		Network network = scenario.getNetwork();
		UamInfrastructure.prepareDresdenOberlaustizUamNetwork(network, serviceArea);
	}

	private static void prepareUamVehicles(Scenario scenario) {
		Id<VehicleType> drtTypeId = Id.create(UAM_MODE, VehicleType.class);
		if (!scenario.getVehicles().getVehicleTypes().containsKey(drtTypeId)) {
			VehicleType drtType = VehicleUtils.createVehicleType(drtTypeId);
			drtType.setMaximumVelocity(200 / 3.6);
			drtType.setNetworkMode(TransportMode.drt);
			drtType.setPcuEquivalents(1);
			drtType.setLength(7.5);
			VehicleCapacity capacity = drtType.getCapacity();
			capacity.setSeats(8);
			scenario.getVehicles().addVehicleType(drtType);

			Vehicle drtDummy = VehicleUtils.createVehicle(Id.createVehicleId("drtDummy"), drtType);
			drtDummy.getAttributes().putAttribute("dvrpMode", TransportMode.drt);
			drtDummy.getAttributes().putAttribute("startLink", "uam-link-2");
			drtDummy.getAttributes().putAttribute("serviceBeginTime", 0.);
			drtDummy.getAttributes().putAttribute("serviceEndTime", 86400.);
			scenario.getVehicles().addVehicle(drtDummy);
		}
	}

}
