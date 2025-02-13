package org.matsim.prepare;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.emissions.HbefaRoadTypeMapping;
import org.matsim.contrib.emissions.OsmHbefaMapping;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.util.HashSet;
import java.util.Set;

import static org.matsim.run.OberlausitzDresdenScenario.FREIGHT;

@CommandLine.Command(
		name = "network",
		description = "Prepare network / link attributes."
)
public class PrepareNetwork implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(PrepareNetwork.class);

	@CommandLine.Option(names = "--network", description = "Path to network file", required = true)
	private String networkFile;

	@CommandLine.Option(names = "--output", description = "Output path of the prepared network", required = true)
	private String outputPath;

	public static void main(String[] args) {
		new PrepareNetwork().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Network network = NetworkUtils.readNetwork(networkFile);

		prepareFreightNetwork(network);
		prepareEmissionsAttributes(network);

		NetworkUtils.writeNetwork(network, outputPath);

		return 0;
	}

	/**
	 * prepare link attributes for freight and truck as allowed modes together with car.
	 */
	public static void prepareFreightNetwork(Network network) {
		int linkCount = 0;

		for (Link link : network.getLinks().values()) {
			Set<String> modes = Sets.newHashSet(link.getAllowedModes());
			modes.remove(TransportMode.truck);

			// allow freight traffic together with cars
			if (modes.contains(TransportMode.car)) {
				modes.add(FREIGHT);
				linkCount++;
			}
			link.setAllowedModes(modes);
		}

		log.info("For {} links {} has been added as an allowed mode.", linkCount, FREIGHT);

		new MultimodalNetworkCleaner(network).run(Set.of(FREIGHT));
	}

	/**
	 * add hbefa link attributes.
	 */
	public static void prepareEmissionsAttributes(Network network) {
//		do not use VspHbefaRoadTypeMapping() as it results in almost every road to mapped to "highway"!
		HbefaRoadTypeMapping roadTypeMapping = OsmHbefaMapping.build();
		roadTypeMapping.addHbefaMappings(network);
	}

	/**
	 * add drt as allowed mode on links within given shape.
	 */
	public static void prepareDrtNetwork(Network network, String drtAreaShp) {
		//		add drt as allowed mode for whole Lausitz region
		Geometry geometry = new ShpOptions(drtAreaShp, null, null).getGeometry();

//		with the estimator, drt is teleported, but we may need drt as an allowed mode for
//		separate drt post simulation
		for (Link link : network.getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)) {
				boolean isInside = MGC.coord2Point(link.getFromNode().getCoord()).within(geometry) ||
					MGC.coord2Point(link.getToNode().getCoord()).within(geometry);

				if (isInside) {
					Set<String> modes = new HashSet<>();
					modes.add(TransportMode.drt);
					modes.addAll(link.getAllowedModes());
					link.setAllowedModes(modes);
				}
			}
		}
		new MultimodalNetworkCleaner(network).run(Set.of(TransportMode.drt));
	}
}
