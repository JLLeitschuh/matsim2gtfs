package com.conveyal.matsim2gtfs;

import com.beust.jcommander.internal.Maps;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.csvreader.CsvReader;
import com.vividsolutions.jts.geom.LineString;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import org.mapdb.Fun;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.MatsimNetworkReader;
import org.matsim.core.network.NetworkImpl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.router.TransitRouterNetwork;
import org.matsim.pt.transitSchedule.api.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Convert a MatSim XML transit network to GTFS.
 * There are a few assumptions here that fit only with the FCL MatSim network for Singapore.
 */
public class MainFrequency {

    public static final Map<String, String> LIGHT_RAIL_NAMES = Maps.newHashMap();
    public static final Map<String, String> HEAVY_RAIL_NAMES = Maps.newHashMap();
    static {
        // LRT (light rail)
        LIGHT_RAIL_NAMES.put("BP", "Bukit Panjang LRT");
        LIGHT_RAIL_NAMES.put("PE", "Punggol East LRT");
        LIGHT_RAIL_NAMES.put("PW", "Punggol West LRT");
        LIGHT_RAIL_NAMES.put("SE", "Sengkang East LRT");
        LIGHT_RAIL_NAMES.put("SW", "Sengkang West LRT");
        LIGHT_RAIL_NAMES.put("SN", "Sentosa Monorail");
        // MRT (heavy rail)
        HEAVY_RAIL_NAMES.put("CC", "Circle Line MRT");
        HEAVY_RAIL_NAMES.put("CE", "Circle Line MRT Marina Bay Branch");
        HEAVY_RAIL_NAMES.put("DT", "Downtown Line MRT");
        HEAVY_RAIL_NAMES.put("EW", "East-West MRT");
        HEAVY_RAIL_NAMES.put("CG", "East-West MRT Changi Branch");
        HEAVY_RAIL_NAMES.put("NE", "Northeast MRT");
        HEAVY_RAIL_NAMES.put("NS", "North-South MRT");
    }

    public static void main(String[] args) {

        // Transformation to convert Singapore UTM coordinates to WGS84
        CoordinateTransformation cT = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84_UTM48N, TransformationFactory.WGS84);

        final String FEED_ID = args[0];

        // A dummy MatSim scenario into which we will load the XML transit data
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(args[1]);

        Network network = NetworkImpl.createNetwork();
        new MatsimNetworkReader(network).readFile("/Users/abyrd/projects/fclsg/matsim_transit/transitNetworkCEPASST.xml");

        // A GTFS feed into which we will copy the Matsim transit data
        GTFSFeed gtfsFeed = new GTFSFeed();

        // Add a feed_info to declare the feed ID.
        FeedInfo gtfsFeedInfo = new FeedInfo();
        gtfsFeedInfo.feed_id = FEED_ID;
        gtfsFeedInfo.feed_lang = "en_us";
        gtfsFeedInfo.feed_publisher_name = "Conveyal, LLC";
        gtfsFeedInfo.feed_publisher_url = createUrl("http://www.conveyal.com");
        gtfsFeed.feedInfo.put("info", gtfsFeedInfo);

        // Create a single agency in this new GTFS feed.
        Agency gtfsAgency = new Agency();
        gtfsAgency.agency_id = FEED_ID;
        gtfsAgency.agency_name = FEED_ID;
        gtfsAgency.agency_timezone = "America/New_York";
        gtfsAgency.agency_url = createUrl("http://www.conveyal.com");
        gtfsFeed.agency.put(gtfsAgency.agency_id, gtfsAgency);

        // A Matsim network describes only a single day of operation.
        // Create a single GTFS service called S which is exactly the same every day, running from year 2000 to 2100.
        Calendar calendar = new Calendar();
        calendar.monday = calendar.tuesday = calendar.wednesday = calendar.thursday = calendar.friday =
                calendar.saturday = calendar.sunday = 1;
        calendar.service_id = "S";
        calendar.start_date = 20000101;
        calendar.end_date = 21000101;

        // Create a gtfs-lib Service object wrapping the Calendar.
        Service gtfsService = new Service(calendar.service_id);
        gtfsService.calendar = calendar;
        gtfsFeed.services.put(gtfsService.service_id, gtfsService);

        // Copy all stops from Matsim scenario to GTFS
        for (TransitStopFacility matsimStop : scenario.getTransitSchedule().getFacilities().values()) {
            Stop gtfsStop = new Stop();
            gtfsStop.stop_id = matsimStop.getId().toString();
            gtfsStop.stop_name = matsimStop.getName();
            Coord wgsCoord = cT.transform(matsimStop.getCoord());
            gtfsStop.stop_lat = wgsCoord.getY();
            gtfsStop.stop_lon = wgsCoord.getX();
            gtfsFeed.stops.put(gtfsStop.stop_id, gtfsStop);
        }

        // Next convert all Matsim TransitLines to GTFS Routes
        for (TransitLine matsimLine : scenario.getTransitSchedule().getTransitLines().values()) {

            Route gtfsRoute = new Route();
            gtfsRoute.feed_id = FEED_ID;
            gtfsRoute.agency_id = FEED_ID;
            gtfsRoute.route_id = matsimLine.getId().toString();
            gtfsRoute.route_short_name = matsimLine.getId().toString();
            if (HEAVY_RAIL_NAMES.containsKey(gtfsRoute.route_id)) {
                gtfsRoute.route_type = 2; // Subway
                gtfsRoute.route_long_name = HEAVY_RAIL_NAMES.get(gtfsRoute.route_id);
            } else if (LIGHT_RAIL_NAMES.containsKey(gtfsRoute.route_id)) {
                gtfsRoute.route_type = 2; // Subway
                gtfsRoute.route_long_name = LIGHT_RAIL_NAMES.get(gtfsRoute.route_id);
            } else {
                gtfsRoute.route_type = 3; // Bus
            }
            gtfsFeed.routes.put(gtfsRoute.route_id, gtfsRoute);

            final int SIX_AM_SECONDS = 7 * 60 * 60;
            final int SEVEN_AM_SECONDS = 7 * 60 * 60;
            final int EIGHT_AM_SECONDS = 8 * 60 * 60;
            final int NINE_AM_SECONDS = 9 * 60 * 60;
            final int TEN_AM_SECONDS = 9 * 60 * 60;


            // A Matsim TransitLine is a collection of Matsim TransitRoutes, which are what Conveyal calls TripPatterns.
            // (A bunch of GTFS trips with the same stop sequence).
            // MatSim's native representation is like GTFS frequencies.txt with exactTimes = true, where all inter-stop
            // times are identical because they haven't yet been subjected to traffic conditions.
            for (TransitRoute matsimRoute : matsimLine.getRoutes().values()) {

                // GET ROUTE SHAPES
                System.out.println("{\n  \"type\": \"Feature\",");
                System.out.println("  \"properties\": {");
                System.out.println("    \"route_type\": " + gtfsRoute.route_type + ",");
                System.out.println("    \"route_short_name\": \"" + gtfsRoute.route_short_name + "\",");
                System.out.println("    \"route_long_name\": \"" + gtfsRoute.route_long_name + "\",");
                System.out.println("    \"route_id\": \"" + matsimRoute.getId().toString() + "\"");
                System.out.println("  },");
                System.out.println("  \"geometry\": {");
                System.out.println("    \"type\": \"LineString\",");
                System.out.print  ("    \"coordinates\": [");



                List<Coord> routeCoords = new ArrayList<>();
                Link link = null;
                for (Id linkId : matsimRoute.getRoute().getLinkIds()) {
                    link = network.getLinks().get(linkId);
                    routeCoords.add(link.getFromNode().getCoord());
                }
                routeCoords.add(link.getToNode().getCoord());
                List<Coord> wgsRouteCoords = routeCoords.stream().map(cT::transform).collect(Collectors.toList());
                wgsRouteCoords.forEach(rc -> System.out.printf("[%f,%f],", rc.getX(), rc.getY()));
                System.out.println("]\n  }");
                System.out.println("},");

                // A Matsim Route is like what Conveyal calls a "trip pattern".
                String matsimRouteId = matsimRoute.getId().toString();
                // The first departure time on a Matsim trip is in seconds after midnight.
                // The Matsim arrival and departure offsets are also in seconds.
                // Copy the departure times into a list for further manipulation, finding the closest one to 8AM.
                TDoubleList departureTimes = new TDoubleArrayList();
                double bestDepartureTime = Double.POSITIVE_INFINITY;
                String bestDepartureId = null;
                for (Departure matsimDeparture : matsimRoute.getDepartures().values()) {
                    double departureTime = matsimDeparture.getDepartureTime();
                    if (departureTime > SEVEN_AM_SECONDS && departureTime < NINE_AM_SECONDS) {
                        departureTimes.add(departureTime);
                        if (departureTime > EIGHT_AM_SECONDS && departureTime < bestDepartureTime) {
                            bestDepartureTime = departureTime;
                            bestDepartureId = matsimDeparture.getId().toString();
                        }
                    }
                }
                // Convert departure times to headways
                departureTimes.sort();
                TDoubleList headways = new TDoubleArrayList();
                for (int i = 0; i < departureTimes.size() - 1; i++) {
                    double headway = departureTimes.get(i + 1) - departureTimes.get(i);
                    headways.add(headway);
                }
                // Weight all headways by themselves (weight them by how much of the time they are experienced)
                TDoubleList squaredHeadways = new TDoubleArrayList(headways);
                squaredHeadways.transformValues(h -> h * h);
                double expectedHeadway = squaredHeadways.sum() / headways.sum();
                // Catch division by zero where headway is undefined, or situations where there is no departure time after 8AM
                if (Double.isNaN(expectedHeadway) || !Double.isFinite(bestDepartureTime)) continue;
                //System.out.printf("Route %s has headway of %.1f minutes\n", matsimRoute.getId().toString(), expectedHeadway/60.0);
                //System.out.printf("");
                // Create a single GTFS Trip to be referenced by the Frequency entry.
                Trip gtfsTrip = new Trip();
                gtfsTrip.route_id = gtfsRoute.route_id;
                gtfsTrip.service_id = "S"; // All trips are on the same service day.
                // We will have one GTFS Trip per Matsim Route, so we can use the Route ID as a Trip ID.
                // But we also want to keep track of which depature ID we want to get the inter-stop times from.
                gtfsTrip.trip_id = matsimRouteId + "#" + bestDepartureId;
                gtfsTrip.trip_headsign = matsimRoute.getStops().get(matsimRoute.getStops().size() - 1).getStopFacility().getName();
                gtfsFeed.trips.put(gtfsTrip.trip_id, gtfsTrip);
                // Create a GTFS frequency entry referencing this MatSim TransitRoute via the GTFS Trip created above.
                // The frequency entry is larger to avoid any time window edge effects in analyses.
                Frequency frequency = new Frequency();
                frequency.trip_id = gtfsTrip.trip_id;
                frequency.start_time = SIX_AM_SECONDS;
                frequency.end_time = TEN_AM_SECONDS;
                frequency.exact_times = 0;
                frequency.headway_secs = (int) expectedHeadway;
                gtfsFeed.frequencies.add(new Fun.Tuple2<>(frequency.trip_id, frequency));
                // Disabled making stop times because the Matsim transit schedule contains only a single set of
                // uncongested travel times per day. We want to use the actual arrival and departure times after
                // many iterations of Matsim, which are processed manually.
                /*
                // The first departure time on a Matsim trip is in seconds after midnight.
                // The Matsim arrival and departure offsets are also in seconds.
                double firstDepartureTime = matsimDeparture.getDepartureTime();
                int stopWithinTrip = 0;
                for (TransitRouteStop trs : matsimRoute.getStops()) {
                    StopTime gtfsStopTime = new StopTime();
                    gtfsStopTime.trip_id = gtfsTrip.trip_id;
                    gtfsStopTime.stop_sequence = stopWithinTrip;
                    gtfsStopTime.stop_id = trs.getStopFacility().getId().toString();
                    gtfsStopTime.arrival_time = (int) (firstDepartureTime + trs.getArrivalOffset());
                    gtfsStopTime.departure_time = (int) (firstDepartureTime + trs.getDepartureOffset());
                    gtfsFeed.stop_times.put(
                            new Fun.Tuple2(gtfsStopTime.trip_id, Integer.valueOf(gtfsStopTime.stop_sequence)),
                            gtfsStopTime);
                    stopWithinTrip += 1;
                }
                */
            }

        }

        // Now create stop_times.txt separately, from the Singapore Matsim model events output.
        // NB: This file must have at least the departureId field quoted, because those IDs contain commas.
        try {
            CsvReader events = new CsvReader("departuresAndArrivals.csv");
            events.readHeaders();
            while (events.readRecord()) {
                // Most or all arrivals are equal to departures in the same stop_time. For simplicity skip the arrivals.
                String eventType = events.get("eventType");
                if (eventType.equals("arrival")) continue;
                // Matsim routeId concatenated with the departureId within the route should make a unique trip ID.
                String tripId = events.get("routeId") + "#" + events.get("departureId");
                // If we did not create a GTFS trip for this ID, no GTFS frequency is referencing this stop_time. Skip it.
                if (!gtfsFeed.trips.containsKey(tripId)) continue;
                String stopId = events.get("stopId");
                int time = Integer.parseInt(events.get("time"));
                StopTime gtfsStopTime = new StopTime();
                gtfsStopTime.trip_id = tripId;
                gtfsStopTime.stop_sequence = time;
                gtfsStopTime.stop_id = stopId;
                gtfsStopTime.arrival_time = time;
                gtfsStopTime.departure_time = time;
                gtfsFeed.stop_times.put(
                        new Fun.Tuple2(gtfsStopTime.trip_id, Integer.valueOf(gtfsStopTime.stop_sequence)),
                        gtfsStopTime);
            }
            events.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Write out to GTFS
        gtfsFeed.toFile(args[2]);

    }

    /**
     * Circumvent Java "safety".
     */
    private static URL createUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }

}
