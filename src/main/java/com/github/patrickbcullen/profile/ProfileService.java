package com.github.patrickbcullen.profile;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;

@Path("api")
public class ProfileService {

    private final KafkaStreams streams;
    private final String profileStoreName;
    private final String searchStoreName;
    private Server jettyServer;
    private final KafkaProducer<String, ProfileEvent> profileProducer;
    private final String topic;
    private final HostInfo hostInfo;
    private final Client client = ClientBuilder.newBuilder().register(JacksonFeature.class).build();

    ProfileService(final KafkaStreams streams, final HostInfo hostInfo, final String profileEventsTopic, final String profileStoreName, final String searchStoreName,
                   final String bootstrapServers) {
        this.streams = streams;
        this.hostInfo = hostInfo;
        this.profileStoreName = profileStoreName;
        this.searchStoreName = searchStoreName;
        this.topic = profileEventsTopic;
        Map<String, Object> serdeProps = new HashMap<>();
        final Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        final Serializer<ProfileEvent> profileEventSerializer = new JsonPOJOSerializer<>();
        serdeProps.put("JsonPOJOClass", ProfileEvent.class);
        profileEventSerializer.configure(serdeProps, false);

        this.profileProducer = new KafkaProducer<>(producerProps, new StringSerializer(), profileEventSerializer);

    }

    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public ProfileBean searchProfile(@QueryParam("email") String email) {
        final HostStoreInfo host = hostInfoForStoreAndKey(profileStoreName, email, new StringSerializer());
        if (!host.equivalent(hostInfo)) {
            return fetchProfile(host, "search?email=" + email);
        }
        ReadOnlyKeyValueStore<String, ProfileBean> stateStore = waitUntilStoreIsQueryable(searchStoreName);
        return findProfileByKey(email, stateStore);
    }

    @GET
    @Path("/profile/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ProfileBean getProfileByID(@PathParam("id") String id) {
        final HostStoreInfo host = hostInfoForStoreAndKey(profileStoreName, id, new StringSerializer());
        if (!host.equivalent(hostInfo)) {
            return fetchProfile(host, "profile/" + id);
        }
        ReadOnlyKeyValueStore<String, ProfileBean> stateStore = waitUntilStoreIsQueryable(profileStoreName);
        return findProfileByKey(id, stateStore);
    }

    @PUT
    @Path("/profile/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateProfile(@PathParam("id") String id, ProfileBean profile) {
        profileProducer.send(new ProducerRecord<String, ProfileEvent>(topic, id, new ProfileEvent("update", profile)));
        return Response.status(200).entity(profile).build();
    }

    @POST
    @Path("/profile")
    @Produces(MediaType.APPLICATION_JSON)
    public Response createProfile(ProfileBean profile) {
        profile.uid = newUUID();
        profileProducer.send(new ProducerRecord<String, ProfileEvent>(topic, profile.uid, new ProfileEvent("create", profile)));
        return Response.status(201).entity(profile).build();
    }

    @DELETE
    @Path("/profile/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteProfile(@PathParam("id") String id) {
        profileProducer.send(new ProducerRecord<String, ProfileEvent>(topic, id, new ProfileEvent("delete", id)));
        return Response.status(204).build();
    }

    private ProfileBean fetchProfile(final HostStoreInfo host, final String path) {
        return client.target(String.format("http://%s:%d/%s", host.getHost(), host.getPort(), path))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .get(new GenericType<ProfileBean>(){});
    }

    private ProfileBean findProfileByKey(String key, ReadOnlyKeyValueStore<String, ProfileBean> stateStore) {
        final ProfileBean value = stateStore.get(key);
        if (value == null) {
            throw new NotFoundException();
        }
        return value;
    }

    private ReadOnlyKeyValueStore<String, ProfileBean> waitUntilStoreIsQueryable(final String storeName) throws InvalidStateStoreException {
        final long maxWaitMillis = 3000;
        long currentWaitMillis = 0;

        while (true) {
            try {
                return streams.store(storeName, QueryableStoreTypes.<String, ProfileBean>keyValueStore());
            } catch (InvalidStateStoreException ex) {
                // store not yet ready for querying
                if (currentWaitMillis >= maxWaitMillis) {
                    throw ex;
                }
                currentWaitMillis += 100;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignore) {
                    //ignore this exception
                }
            }
        }
    }
    private String newUUID() {
        return String.valueOf(UUID.randomUUID());
    }

    void start(final int port) throws Exception {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        jettyServer = new Server(port);
        jettyServer.setHandler(context);

        ResourceConfig rc = new ResourceConfig();
        rc.register(this);
        rc.register(JacksonFeature.class);

        ServletContainer sc = new ServletContainer(rc);
        ServletHolder holder = new ServletHolder(sc);
        context.addServlet(holder, "/*");

        jettyServer.start();
    }

    void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
    }

    private HostStoreInfo hostInfoForStoreAndKey(final String store, final String key,
                                                           final Serializer<String> serializer) {
        final StreamsMetadata metadata = streams.metadataForKey(store, key, serializer);
        if (metadata == null) {
            throw new NotFoundException();
        }

        return new HostStoreInfo(metadata.host(),
                metadata.port(),
                metadata.stateStoreNames());
    }
}
