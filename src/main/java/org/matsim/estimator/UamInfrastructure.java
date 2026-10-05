package org.matsim.estimator;

import org.apache.commons.lang3.tuple.Pair;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class UamInfrastructure {
	private static final String DRESDEN = "Dresden";
	private static final String HOYERSWERDA = "Hoyerswerda";
	private static final String LEIPZIG = "Leipzig";
	private static final String COTTBUS = "Cottbus";
	private static final String CHEMNITZ = "Chemnitz";

	public static void prepareUamNetwork(Network network, String serviceAreaShp, Map<String, Set<Coord>> vertiportsLocationsPerRegions,
										 Set<Pair<String, String>> connections) {
		Set<Node> relevantNodes = new HashSet<>();
		for (Node node : network.getNodes().values()) {
			Set<Link> linksConnectedToNode = new HashSet<>();
			linksConnectedToNode.addAll(node.getOutLinks().values());
			linksConnectedToNode.addAll(node.getInLinks().values());
			for (Link link : linksConnectedToNode) {
				if (link.getAllowedModes().contains(TransportMode.car)) {
					relevantNodes.add(node);
					break;
				}
			}
		}

		// step 1. build UAM flying network (connect)
		int counter = 0;
		for (Pair<String, String> connection : connections) {
			for (Coord coord1 : vertiportsLocationsPerRegions.get(connection.getLeft())) {
				for (Coord coord2 : vertiportsLocationsPerRegions.get(connection.getRight())) {
					Node node1 = getClosestNode(coord1, relevantNodes);
					Node node2 = getClosestNode(coord2, relevantNodes);
					assert node1 != null;
					assert node2 != null;
					assert !node1.getCoord().equals(node2.getCoord());

					double distance = CoordUtils.calcEuclideanDistance(node1.getCoord(), node2.getCoord());

					Link link1 = NetworkUtils.createLink(Id.createLinkId("uam-link-" + counter), node1, node2, network, distance, 200 / 3.6, 3600, 1);
					link1.setAllowedModes(Set.of(TransportMode.drt));
					network.addLink(link1);
					counter++;

					Link link2 = NetworkUtils.createLink(Id.createLinkId("uam-link-" + counter), node2, node1, network, distance, 200 / 3.6, 3600, 1);
					link2.setAllowedModes(Set.of(TransportMode.drt));
					network.addLink(link2);
					counter++;
				}
			}
		}

		// step 2. add ground transport to the uam network
		ShpOptions shp = new ShpOptions(serviceAreaShp, "EPSG:25832", null);
		Geometry serviceArea = shp.getGeometry();
		for (Link link : network.getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)) {
				if (MGC.coord2Point(link.getToNode().getCoord()).within(serviceArea) && MGC.coord2Point(link.getFromNode().getCoord()).within(serviceArea)) {
					Set<String> updatedAllowedModes = new HashSet<>(link.getAllowedModes());
					updatedAllowedModes.add(TransportMode.drt);
					link.setAllowedModes(updatedAllowedModes);
				}
			}
		}

		// step 3. clean the uam network
		new MultimodalNetworkCleaner(network).run(Set.of(TransportMode.drt));
	}

	// Prepare Oberlausitz Dresden UAM network
	public static void prepareDresdenOberlaustizUamNetwork(Network network, String serviceArea) {
		Set<Pair<String, String>> connections = UamInfrastructure.prepareConnections();
		Map<String, Set<Coord>> vertiportLocationsPerRegions = UamInfrastructure.prepareVertiportsLocationsPerRegions(network);
		UamInfrastructure.prepareUamNetwork(network, serviceArea, vertiportLocationsPerRegions, connections);
	}

	private static Map<String, Set<Coord>> prepareVertiportsLocationsPerRegions(Network network) {
		Map<String, Set<Coord>> vertiportsLocationsPerRegions = new HashMap<>();

		// Dresden: the only region with two vertiports, one on each side of the Elbe
		vertiportsLocationsPerRegions.put(DRESDEN, new HashSet<>());
		// DD Hbf (Altstadt)
		vertiportsLocationsPerRegions.get(DRESDEN).add(getNodeCoord(network, "11768490"));
		// DD Albertplatz (Neustadt)
		vertiportsLocationsPerRegions.get(DRESDEN).add(getNodeCoord(network, "24969766"));

		// Hoyerswerda: HY Altstadt (city centre)
		vertiportsLocationsPerRegions.put(HOYERSWERDA, new HashSet<>());
		vertiportsLocationsPerRegions.get(HOYERSWERDA).add(getNodeCoord(network, "1701429426"));

		// Leipzig: city centre, east of Augustusplatz
		vertiportsLocationsPerRegions.put(LEIPZIG, new HashSet<>());
		vertiportsLocationsPerRegions.get(LEIPZIG).add(getNodeCoord(network, "254599114"));

		// Cottbus: city centre, between Hbf and Altmarkt
		vertiportsLocationsPerRegions.put(COTTBUS, new HashSet<>());
		vertiportsLocationsPerRegions.get(COTTBUS).add(getNodeCoord(network, "2339274980"));

		// Chemnitz: city centre
		vertiportsLocationsPerRegions.put(CHEMNITZ, new HashSet<>());
		vertiportsLocationsPerRegions.get(CHEMNITZ).add(getNodeCoord(network, "3888083281"));

		return vertiportsLocationsPerRegions;
	}

	private static Set<Pair<String, String>> prepareConnections() {
		Set<Pair<String, String>> connections = new HashSet<>();
		// Connection between Dresden and other bubbles
		connections.add(Pair.of(DRESDEN, HOYERSWERDA));
		connections.add(Pair.of(DRESDEN, LEIPZIG));
		connections.add(Pair.of(DRESDEN, CHEMNITZ));
		connections.add(Pair.of(DRESDEN, COTTBUS));
		// Connection not linked to Dresden
//		connections.add(Pair.of(COTTBUS, LEIPZIG));
//		connections.add(Pair.of(COTTBUS, HOYERSWERDA));
//		connections.add(Pair.of(LEIPZIG, CHEMNITZ));
		return connections;
	}

	private static Coord getNodeCoord(Network network, String nodeId) {
		Node node = network.getNodes().get(Id.createNodeId(nodeId));
		if (node == null) {
			throw new IllegalArgumentException("Vertiport node " + nodeId + " does not exist in the network. " +
				"Please check whether the correct network file is used.");
		}
		return node.getCoord();
	}

	private static Node getClosestNode(Coord coord, Set<Node> relevantNodes) {
		Node nearestNode = null;
		double minDistance = Double.POSITIVE_INFINITY;

		for (Node node : relevantNodes) {
			double dist = CoordUtils.calcEuclideanDistance(coord, node.getCoord());
			if (dist < minDistance) {
				nearestNode = node;
				minDistance = dist;
			}
		}
		return nearestNode;
	}

}

