package org.matsim.dashboard;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.run.OberlausitzDresdenScenario;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.DashboardProvider;
import org.matsim.simwrapper.SimWrapper;
import org.matsim.simwrapper.dashboard.TrafficCountsDashboard;
import org.matsim.simwrapper.dashboard.TripDashboard;

import java.util.List;
import java.util.Set;

/**
 * Provider for default dashboards in the scenario.
 * Declared in META-INF/services
 */
public class OberlausitzDresdenDashboardProvider implements DashboardProvider {

	@Override
	public List<Dashboard> getDashboards(Config config, SimWrapper simWrapper) {

		TripDashboard trips = new TripDashboard(
			"mode_share_ref.csv", "mode_share_per_dist_ref.csv", "mode_users_ref.csv")
			.withGroupedRefData("mode_share_per_group_dist_ref.csv", "age", "economic_status")
			.withDistanceDistribution("mode_share_distance_distribution.csv")
			.setAnalysisArgs("--dist-groups", "0,1000,2000,5000,10000,20000");

		TrafficCountsDashboard counts = new TrafficCountsDashboard()
			.withModes(TransportMode.car, Set.of(TransportMode.car))
			.withModes(OberlausitzDresdenScenario.FREIGHT, Set.of(OberlausitzDresdenScenario.FREIGHT));

		return List.of(trips, counts);
	}

}
