/* (c) 2014 - 2016 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.cluster.impl.handlers.configuration;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.apache.commons.lang.NullArgumentException;
import org.geoserver.catalog.impl.ModificationProxy;
import org.geoserver.cluster.events.ToggleSwitch;
import org.geoserver.cluster.impl.events.configuration.JMSServiceModifyEvent;
import org.geoserver.cluster.impl.utils.BeanUtils;
import org.geoserver.config.GeoServer;
import org.geoserver.config.ServiceInfo;

import com.thoughtworks.xstream.XStream;

import static org.geoserver.cluster.impl.events.configuration.JMSServiceModifyEvent.Type.ADDED;
import static org.geoserver.cluster.impl.events.configuration.JMSServiceModifyEvent.Type.MODIFIED;
import static org.geoserver.cluster.impl.events.configuration.JMSServiceModifyEvent.Type.REMOVED;

/**
 * 
 * @see {@link JMSServiceHandlerSPI}
 * @author Carlo Cancellieri - carlo.cancellieri@geo-solutions.it
 * 
 */
public class JMSServiceHandler extends JMSConfigurationHandler<JMSServiceModifyEvent> {
    private final GeoServer geoServer;

    private final ToggleSwitch producer;

    public JMSServiceHandler(GeoServer geo, XStream xstream, Class clazz, ToggleSwitch producer) {
        super(xstream, clazz);
        this.geoServer = geo;
        this.producer = producer;
    }

    @Override
    protected void omitFields(final XStream xstream) {
        // omit not serializable fields
        xstream.omitField(GeoServer.class, "geoServer");
    }

    @Override
    public boolean synchronize(JMSServiceModifyEvent ev) throws Exception {
        if (ev == null) {
            throw new NullArgumentException("Incoming event is null");
        }
        try {
            // disable the message producer to avoid recursion
            producer.disable();
            // let's see which type of event we have
            switch (ev.getEventType()) {
                case MODIFIED:
                    // localize service
                    final ServiceInfo localObject = localizeService(geoServer, ev);
                    // save the localized object
                    geoServer.save(localObject);
                    break;
                case ADDED:
                    // checking that this service is not already present, we don't synchronize this check
                    // if two threads add the same service well one of them will fail and throw an exception
                    if (geoServer.getService(ev.getSource().getId(), ev.getSource().getClass()) == null) {
                        // this is a new service so let's add it to this geoserver
                        geoServer.add(ev.getSource());
                    }
                    break;
                case REMOVED:
                    // this service was removed so let's remove it from this geoserver
                    geoServer.remove(ev.getSource());
                    break;
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(java.util.logging.Level.SEVERE))
                LOGGER.severe(this.getClass() + " is unable to synchronize the incoming event: "
                        + ev);
            throw e;
        } finally {
            producer.enable();
        }
        return true;

    }

    /**
     * Starting from an incoming de-serialized ServiceInfo modify event, search for it (by name) into local geoserver and update changed members.
     * 
     * @param geoServer local GeoServer instance
     * @param ev the incoming event
     * @return the localized and updated ServiceInfo to save
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     */
    private static ServiceInfo localizeService(final GeoServer geoServer,
            final JMSServiceModifyEvent ev) throws IllegalAccessException,
            InvocationTargetException, NoSuchMethodException {
        if (geoServer == null || ev == null)
            throw new IllegalArgumentException("wrong passed arguments are null");

        final ServiceInfo info = JMSServiceHandler.getLocalService(geoServer, ev);

        BeanUtils.smartUpdate(info, ev.getPropertyNames(), ev.getNewValues());

        // LOCALIZE service
        info.setGeoServer(geoServer);

        return info;
    }

    /**
     * get local object searching by name if name is changed (remotely), search is performed using the old one
     * 
     * @param geoServer
     * @param ev
     * @return
     */
    public static ServiceInfo getLocalService(final GeoServer geoServer,
            final JMSServiceModifyEvent ev) {

        final ServiceInfo service = ev.getSource();
        if (service == null) {
            throw new IllegalArgumentException("passed service is null");
        }

        // localize service
        final ServiceInfo localObject;

        // check if name is changed
        final List<String> props = ev.getPropertyNames();
        final int index = props.indexOf("name");
        String serviceName = service.getName();
        if (index != -1) {
            // the service name was updated so we need to use old service name
            final List<Object> oldValues = ev.getOldValues();
            serviceName = oldValues.get(index).toString();
        }
        if (service.getWorkspace() == null) {
            // no virtual service
            return geoServer.getServiceByName(serviceName, ServiceInfo.class);
        }
        // globals service
        return geoServer.getServiceByName(service.getWorkspace(), serviceName, ServiceInfo.class);
    }

}
