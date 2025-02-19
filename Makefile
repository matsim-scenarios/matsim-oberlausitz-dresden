
N := oberlausitz-dresden
V := v2025.0
CRS := EPSG:25832

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../Sumo/)
endif

#define some important paths
# osmosis and sumo paths need to be in " because of blank space in path...
osmosis := "$(CURDIR)/../../../../../Program Files/osmosis-0.49.2//bin/osmosis.bat"
germany := $(CURDIR)/../../shared-svn/projects/matsim-germany
shared := $(CURDIR)/../../shared-svn/projects/matsim-oberlausitz-dresden
oberlausitz-dresden := $(CURDIR)/../../public-svn/matsim/scenarios/countries/de/oberlausitz-dresden/oberlausitz-dresden-$V/input/

MEMORY ?= 30G
#JAR := matsim-$(N)-*.jar
JAR := matsim-oberlausitz-dresden-2025.0-bdff902-dirty.jar
NETWORK := $(germany)/maps/germany-250127.osm.pbf



# Scenario creation tool
sc := java -Xms$(MEMORY) -Xmx$(MEMORY) -jar $(JAR)

.PHONY: prepare

$(JAR):
	mvn package -DskipTests

# Required files
#this step is only necessary once. The downloaded network is uploaded to shared-svn/projects/matsim-germany/maps
#input/network.osm.pbf:
#	curl https://download.geofabrik.de/europe/germany-250127.osm.pbf\
#	  -o ../../shared-svn/projects/matsim-germany/maps/germany-250127.osm.pbf

#retrieve detailed network (see param highway) from OSM
input/network-detailed.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="$(shared)/data/dresden.poly"\
	 --used-node --wb $@

# 	This includes residential as well, since multiple cities are covered by the study area
#	retrieve coarse network (see param highway) from OSM
input/network-coarse.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential\
	 --bounding-polygon file="$(shared)/data/oberlausitz.poly"\
	 --used-node --wb $@

  #	retrieve germany wide network (see param highway) from OSM
input/network-germany.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
 	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
 	 --used-node --wb $@

input/network.osm: input/network-germany.osm.pbf input/network-coarse.osm.pbf input/network-detailed.osm.pbf
	$(osmosis) --rb file=$< --rb file=$(word 2,$^) --rb file=$(word 3,$^)\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm ./input/network-detailed.osm.pbf
	rm ./input/network-coarse.osm.pbf
	rm ./input/network-germany.osm.pbf


	#	roadTypes are taken either from the general file "osmNetconvert.typ.xml"
	#	or from the german one "osmNetconvertUrbanDe.ty.xml"
input/sumo.net.xml: input/network.osm

	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\
	 --osm-files $< -o=$@

# transform sumo network to matsim network and clean it afterwards
# free-speed-factor 0.75 (standard is 0.9): see VSP WP 24-08. oberlausitz + dresden is mix between rural and city (~0.7 - 0.8)
input/v2025.0/oberlausitz-dresden-v2025.0-network.xml.gz: input/sumo.net.xml
	echo input/$V/$N-$V-network.xml.gz
	$(sc) prepare network-from-sumo $< --output $@ --free-speed-factor 0.75
	$(sc) prepare clean-network $@ --output $@ --modes car --modes bike --modes ride
#	delete truck as allowed mode (not used), add longDistanceFreight as allowed mode, prepare network for emissions analysis
	$(sc) prepare network\
	 --network $@\
	 --output $@


input/v2025.0/oberlausitz-dresden-v2025.0-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2025-02-09" --target-crs $(CRS) \
	 $(shared)/data/gtfs/20250209_regio.zip\
	 $(shared)/data/gtfs/20250209_train_short.zip\
	 $(shared)/data/gtfs/20250209_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp\
	 --shp $(shared)/data/germany-area/germany-area.shp\

# extract oberlausitz-dresden long haul freight traffic trips from german wide file
input/plans-longHaulFreight.xml.gz: input/$V/$N-$V-network.xml.gz
	$(sc) prepare extract-freight-trips ../../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/german_freight.100pct.plans.xml.gz\
	 --network ../../public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz\
	 --input-crs $(CRS)\
	 --target-crs $(CRS)\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
	 --cut-on-boundary\
	 --LegMode "longDistanceFreight"\
	 --output $@

# trajectory-to-plans formerly was a collection of methods to prepare a given population
# now, most of the functions of this class do have their own class (downsample, splitduration types...)
# it basically only transforms the old attribute format to the new one
# --max-typical-duration set to 0 because this switches off the duration split, which we do later
input/v2025.0/prepare-100pct.plans.xml.gz:
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/$V\
	 --max-typical-duration 0\
	 --population $(shared)/data/snz/senozon/20250123_Teilmodell_Hoyerswerda/Modell/population.xml.gz\
	 --attributes  $(shared)/data/snz/senozon/20250123_Teilmodell_Hoyerswerda/Modell/personAttributes.xml.gz

	# resolve senozon aggregated grid coords (activities): distribute them based on landuse.shp
	$(sc) prepare resolve-grid-coords\
	 input/$V/prepare-100pct.plans.xml.gz\
	 --input-crs $(CRS)\
	 --grid-resolution 300\
	 --landuse $(germany)/landuse/landuse.shp\
	 --output $@

 input/v2025.0/oberlausitz-dresden-v2025.0-100pct.plans-initial.xml.gz: input/plans-longHaulFreight.xml.gz input/$V/prepare-100pct.plans.xml.gz
# generate some short distance trips, which in senozon data generally are missing
# trip range 700m because:
# when adding 1km trips (default value), too many trips of bin 1km-2km were also added.
#the range value is beeline, so the trip distance (routed) often is higher than 1km
#TODO: here, we need to differ between dresden and oberlausitz-dresden population for different calibs. One is based on Srv, the other on MiD.
	$(sc) prepare generate-short-distance-trips\
  	 --population input/$V/prepare-100pct.plans.xml.gz\
  	 --input-crs $(CRS)\
 	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
 	 --range 700\
# 	 TODO: adapt number of trips
  	 --num-trips 324430

#	adapt coords of activities in the wider network such that they are closer to a link
# 	such that agents do not have to walk as far as before
	$(sc) prepare adjust-activity-to-link-distances input/$V/prepare-100pct.plans-with-trips.xml.gz\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
     --scale 1.15\
     --input-crs $(CRS)\
     --network input/$V/$N-$V-network.xml.gz\
     --output input/$V/prepare-100pct.plans-adj.xml.gz

#	change modes in subtours with chain based AND non-chain based by choosing mode for subtour randomly
	$(sc) prepare fix-subtour-modes --coord-dist 100 --input input/$V/prepare-100pct.plans-adj.xml.gz --output $@

#	set car availability for agents below 18 to false, standardize some person attrs, set home coords, set person income
	$(sc) prepare population $@ --output $@

#	split activity types to type_duration for the scoring to take into account the typical duration
	$(sc) prepare split-activity-types-duration\
		--input $@\
		--exclude commercial_start,commercial_end,freight_start,freight_end,service\
		--output $@

#	merge person and freight pops
	$(sc) prepare merge-populations $@ $< --output $@

	$(sc) prepare downsample-population $@\
    	 --sample-size 1\
    	 --samples 0.25 0.1 0.01\

# create matsim counts file
input/v2025.0/oberlausitz-dresden-v2025.0-counts-bast.xml.gz: input/$V/$N-$V-network-with-pt.xml.gz
	$(sc) prepare counts-from-bast\
		--network $<\
		--motorway-data $(germany)/bast-counts/2019/2019_A_S.zip\
		--primary-data $(germany)/bast-counts/2019/2019_B_S.zip\
		--station-data $(germany)/bast-counts/2019/Jawe2019.csv\
		--year 2019\
		--shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)\
		--output $@

check: input/$V/$N-$V-100pct.plans-initial.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp $(shared)/data/oberlausitz-area/oberlausitz.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/$V/$N-$V-100pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
	echo "Done"