package org.matsim.estimator;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.filter.function.GeometryTransformation;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.dvrp.passenger.PassengerRequest;
import org.matsim.contrib.dvrp.passenger.PassengerRequestValidator;
import org.matsim.core.utils.geometry.geotools.MGC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.matsim.contrib.dvrp.passenger.DefaultPassengerRequestValidator.EQUAL_FROM_LINK_AND_TO_LINK_CAUSE;

/**
 * In addition to the DefaultPassengerRequestValidator, we also check if the trip is allowed.
 * A trip is allowed if only if it is travelling between two different service regions.
 */
public class ShpBasedDrtRequestValidator implements PassengerRequestValidator {
	private static final String TRIP_NOT_ALLOWED = "trip_not_allowed";
	private final ShpOptions shp;
	private final List<Geometry> serviceRegions;

	public ShpBasedDrtRequestValidator(ShpOptions shp) {
		this.shp = shp;
		this.serviceRegions = getServiceRegions();
	}

	private List<Geometry> getServiceRegions() {
		List<Geometry> serviceRegions = new ArrayList<>();
		for (SimpleFeature feature : shp.readFeatures()) {
			if (feature.getDefaultGeometry() instanceof Geometry geometry) {
				serviceRegions.add(geometry);
			}
		}
		return serviceRegions;
	}

	@Override
	public Set<String> validateRequest(PassengerRequest passengerRequest) {
		// same as in DefaultPassengerRequestValidator, the request is invalid if fromLink == toLink
		if (passengerRequest.getFromLink() == passengerRequest.getToLink()) {
			return Collections.singleton(EQUAL_FROM_LINK_AND_TO_LINK_CAUSE);
		}

		// check if both ends of the trips are within the same service region
		Coord fromCoord = passengerRequest.getFromLink().getToNode().getCoord();
		Coord toCoord = passengerRequest.getToLink().getToNode().getCoord();

		Point fromPoint = MGC.coord2Point(fromCoord);
		Point toPoint = MGC.coord2Point(toCoord);

		Geometry fromRegion = null;
		Geometry toRegion = null;

		for (Geometry serviceRegion : serviceRegions) {
			if (serviceRegion.contains(fromPoint)) {
				fromRegion = serviceRegion;
			}
			if (serviceRegion.contains(toPoint)) {
				toRegion = serviceRegion;
			}

			if (fromRegion != null && toRegion != null) {
				break;
			}
		}

		if (fromRegion != null && toRegion != null && fromRegion != toRegion) {
			return Collections.emptySet();
		}

		return Collections.singleton(TRIP_NOT_ALLOWED);
	}
}
