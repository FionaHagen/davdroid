/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package at.bitfire.davdroid.ui.setup;

import android.content.Context;
import android.text.TextUtils;

import org.slf4j.Logger;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SRVRecord;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.bitfire.dav4android.DavResource;
import at.bitfire.dav4android.UrlUtils;
import at.bitfire.dav4android.exception.DavException;
import at.bitfire.dav4android.exception.HttpException;
import at.bitfire.dav4android.exception.NotFoundException;
import at.bitfire.dav4android.property.AddressbookDescription;
import at.bitfire.dav4android.property.AddressbookHomeSet;
import at.bitfire.dav4android.property.CalendarColor;
import at.bitfire.dav4android.property.CalendarDescription;
import at.bitfire.dav4android.property.CalendarHomeSet;
import at.bitfire.dav4android.property.CalendarTimezone;
import at.bitfire.dav4android.property.CurrentUserPrincipal;
import at.bitfire.dav4android.property.CurrentUserPrivilegeSet;
import at.bitfire.dav4android.property.DisplayName;
import at.bitfire.dav4android.property.ResourceType;
import at.bitfire.dav4android.property.SupportedCalendarComponentSet;
import at.bitfire.davdroid.HttpClient;
import at.bitfire.davdroid.log.StringLogger;
import at.bitfire.davdroid.model.CollectionInfo;
import lombok.Data;
import lombok.Getter;
import android.support.annotation.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class DavResourceFinder {
    public enum Service {
        CALDAV("caldav"),
        CARDDAV("carddav");

        final String name;
        Service(String name) { this.name = name;}
        @Override public String toString() { return name; }
    };

    protected final Context context;
    protected final LoginCredentialsFragment.LoginCredentials credentials;

    protected final Logger log = new StringLogger("DavResourceFinder", true);
    protected OkHttpClient httpClient;

    public DavResourceFinder(@NonNull Context context, @NonNull LoginCredentialsFragment.LoginCredentials credentials) {
		this.context = context;
        this.credentials = credentials;

        httpClient = HttpClient.create(context);
        httpClient = HttpClient.addLogger(httpClient, log);
        httpClient = HttpClient.addAuthentication(httpClient, credentials.userName, credentials.password, credentials.authPreemptive);
	}


    public Configuration findInitialConfiguration() {
        final Configuration.ServiceInfo
                cardDavConfig = findInitialConfiguration(Service.CARDDAV),
                calDavConfig = findInitialConfiguration(Service.CALDAV);

        return new Configuration(
                credentials.userName, credentials.password, credentials.authPreemptive,
                cardDavConfig, calDavConfig,
                log.toString()
        );
    }

    protected Configuration.ServiceInfo findInitialConfiguration(@NonNull Service service) {
        // user-given base URI (either mailto: URI or http(s):// URL)
        final URI baseURI = credentials.uri;

        // domain for service discovery
        String discoveryFQDN = null;

        // put discovered information here
        final Configuration.ServiceInfo config = new Configuration.ServiceInfo();
        log.info("Finding initial {} service configuration", service.name);

        if ("http".equalsIgnoreCase(baseURI.getScheme()) || "https".equalsIgnoreCase(baseURI.getScheme())) {
            final HttpUrl baseURL = HttpUrl.get(baseURI);

            // remember domain for service discovery
            // try service discovery only for https:// URLs because only secure service discovery is implemented
            if ("https".equalsIgnoreCase(baseURL.scheme()))
                discoveryFQDN = baseURI.getHost();

            checkUserGivenURL(baseURL, service, config);

            if (config.principal == null)
                try {
                    config.principal = getCurrentUserPrincipal(baseURL.resolve("/.well-known/" + service.name), service);
                } catch (IOException|HttpException|DavException e) {
                    log.debug("Well-known URL detection failed", e);
                }

        } else if ("mailto".equalsIgnoreCase(baseURI.getScheme())) {
            String mailbox = baseURI.getSchemeSpecificPart();

            int posAt = mailbox.lastIndexOf("@");
            if (posAt != -1)
                discoveryFQDN = mailbox.substring(posAt + 1);
        }

        // Step 2: If user-given URL didn't reveal a principal, search for it: SERVICE DISCOVERY
        if (config.principal == null && discoveryFQDN != null) {
            log.info("No principal found at user-given URL, trying to discover");
            try {
                config.principal = discoverPrincipalUrl(discoveryFQDN, service);
            } catch (IOException|HttpException|DavException e) {
                log.debug(service.name + " service discovery failed", e);
            }
        }

        // return config or null if config doesn't contain useful information
        boolean serviceAvailable = config.principal != null || !config.homeSets.isEmpty() || !config.collections.isEmpty();
        return serviceAvailable ? config : null;
    }

    protected void checkUserGivenURL(@NonNull HttpUrl baseURL, @NonNull Service service, @NonNull Configuration.ServiceInfo config) {
        log.info("Checking user-given URL: " + baseURL.toString());

        HttpUrl principal = null;
        try {
            DavResource davBase = new DavResource(log, httpClient, baseURL);

            if (service == Service.CARDDAV) {
                davBase.propfind(0,
                        ResourceType.NAME, DisplayName.NAME, AddressbookDescription.NAME,
                        AddressbookHomeSet.NAME,
                        CurrentUserPrincipal.NAME
                );
                rememberIfAddressBookOrHomeset(davBase, config);

            } else if (service == Service.CALDAV) {
                davBase.propfind(0,
                        ResourceType.NAME, DisplayName.NAME, CalendarColor.NAME, CalendarDescription.NAME, CalendarTimezone.NAME, CurrentUserPrivilegeSet.NAME, SupportedCalendarComponentSet.NAME,
                        CalendarHomeSet.NAME,
                        CurrentUserPrincipal.NAME
                );
                rememberIfCalendarOrHomeset(davBase, config);
            }

            // check for current-user-principal
            CurrentUserPrincipal currentUserPrincipal = (CurrentUserPrincipal)davBase.properties.get(CurrentUserPrincipal.NAME);
            if (currentUserPrincipal != null && currentUserPrincipal.href != null)
                principal = davBase.location.resolve(currentUserPrincipal.href);

            // check for resource type "principal"
            if (principal == null) {
                ResourceType resourceType = (ResourceType)davBase.properties.get(ResourceType.NAME);
                if (resourceType.types.contains(ResourceType.PRINCIPAL))
                    principal = davBase.location;
            }

            // If a principal has been detected successfully, ensure that it provides the required service.
            if (principal != null && providesService(principal, service))
                config.principal = principal;

        } catch (IOException|HttpException|DavException e) {
            log.debug("PROPFIND/OPTIONS on user-given URL failed", e);
        }
    }

    protected void rememberIfAddressBookOrHomeset(@NonNull DavResource dav, @NonNull Configuration.ServiceInfo config) {
        // Is the collection an address book?
        ResourceType resourceType = (ResourceType)dav.properties.get(ResourceType.NAME);
        if (resourceType != null && resourceType.types.contains(ResourceType.ADDRESSBOOK)) {
            dav.location = UrlUtils.withTrailingSlash(dav.location);
            log.info("Found address book at " + dav.location);
            config.collections.put(dav.location, CollectionInfo.fromDavResource(dav));
        }

        // Does the collection refer to address book homesets?
        AddressbookHomeSet homeSets = (AddressbookHomeSet)dav.properties.get(AddressbookHomeSet.NAME);
        if (homeSets != null)
            for (String href : homeSets.hrefs)
                config.homeSets.add(UrlUtils.withTrailingSlash(dav.location.resolve(href)));
    }

    protected void rememberIfCalendarOrHomeset(@NonNull DavResource dav, @NonNull Configuration.ServiceInfo config) {
        // Is the collection a calendar collection?
        ResourceType resourceType = (ResourceType)dav.properties.get(ResourceType.NAME);
        if (resourceType != null && resourceType.types.contains(ResourceType.CALENDAR)) {
            dav.location = UrlUtils.withTrailingSlash(dav.location);
            log.info("Found calendar collection at " + dav.location);
            config.collections.put(dav.location, CollectionInfo.fromDavResource(dav));
        }

        // Does the collection refer to calendar homesets?
        CalendarHomeSet homeSets = (CalendarHomeSet)dav.properties.get(CalendarHomeSet.NAME);
        if (homeSets != null)
            for (String href : homeSets.hrefs)
                config.homeSets.add(UrlUtils.withTrailingSlash(dav.location.resolve(href)));
    }


    boolean providesService(HttpUrl url, Service service) throws IOException {
        DavResource davPrincipal = new DavResource(log, httpClient, url);
        try {
            davPrincipal.options();

            if ((service == Service.CARDDAV && davPrincipal.capabilities.contains("addressbook")) ||
                (service == Service.CALDAV && davPrincipal.capabilities.contains("calendar-access")))
                return true;

        } catch (HttpException|DavException e) {
            log.error("Couldn't detect services on {}", url);
        }
        return false;
    }


    /**
     * Try to find the principal URL by performing service discovery on a given domain name.
     * Only secure services (caldavs, carddavs) will be discovered!
     * @param domain         domain name, e.g. "icloud.com"
     * @param service        service to discover (CALDAV or CARDDAV)
     * @return principal URL, or null if none found
     */
    protected HttpUrl discoverPrincipalUrl(String domain, Service service) throws IOException, HttpException, DavException {
        String scheme = null;
        String fqdn = null;
        Integer port = 443;
        List<String> paths = new LinkedList<>();     // there may be multiple paths to try

        final String query = "_" + service.name + "s._tcp." + domain;
        log.debug("Looking up SRV records for " + query);
        Record[] records = new Lookup(query, Type.SRV).run();
        if (records != null && records.length >= 1) {
            // choose SRV record to use (query may return multiple SRV records)
            SRVRecord srv = selectSRVRecord(records);

            scheme = "https";
            fqdn = srv.getTarget().toString(true);
            port = srv.getPort();
            log.info("Found " + service + " service at https://" + fqdn + ":" + port);

        } else {
            // no SRV records, try domain name as FQDN
            log.info("Didn't find " + service + " service, trying at https://" + domain + ":" + port);

            scheme = "https";
            fqdn = domain;
        }

        // look for TXT record too (for initial context path)
        records = new Lookup(query, Type.TXT).run();
        if (records != null)
            for (Record record : records)
                if (record instanceof TXTRecord)
                    for (String segment : (List<String>) ((TXTRecord) record).getStrings())
                        if (segment.startsWith("path=")) {
                            paths.add(segment.substring(5));
                            log.info("Found TXT record; initial context path=" + paths);
                            break;
                        }

        // if there's TXT record and if it it's wrong, try well-known
        paths.add("/.well-known/" + service.name);
        // if this fails, too, try "/"
        paths.add("/");

        for (String path : paths)
            try {
                if (!TextUtils.isEmpty(scheme) && !TextUtils.isEmpty(fqdn) && paths != null) {
                    HttpUrl initialContextPath = new HttpUrl.Builder()
                            .scheme(scheme)
                            .host(fqdn).port(port)
                            .encodedPath(path)
                            .build();

                    log.info("Trying to determine principal from initial context path=" + initialContextPath);
                    HttpUrl principal = getCurrentUserPrincipal(initialContextPath, service);

                    if (principal != null)
                        return principal;
                }
            } catch(NotFoundException e) {
                log.warn("No resource found", e);
            }
        return null;
    }

    /**
     * Queries a given URL for current-user-principal
     * @param url       URL to query with PROPFIND (Depth: 0)
     * @param service   required service (may be null, in which case no service check is done)
     * @return          current-user-principal URL that provides required service, or null if none
     */
    public HttpUrl getCurrentUserPrincipal(HttpUrl url, Service service) throws IOException, HttpException, DavException {
        DavResource dav = new DavResource(log, httpClient, url);
        dav.propfind(0, CurrentUserPrincipal.NAME);
        CurrentUserPrincipal currentUserPrincipal = (CurrentUserPrincipal) dav.properties.get(CurrentUserPrincipal.NAME);
        if (currentUserPrincipal != null && currentUserPrincipal.href != null) {
            HttpUrl principal = dav.location.resolve(currentUserPrincipal.href);
            if (principal != null) {
                log.info("Found current-user-principal: " + principal);

                // service check
                if (service != null && !providesService(principal, service)) {
                    log.info("{} doesn't provide required {} service, dismissing", principal, service);
                    principal = null;
                }

                return principal;
            }
        }
        return null;
    }


    // helpers

	private SRVRecord selectSRVRecord(Record[] records) {
		if (records.length > 1)
			log.warn("Multiple SRV records not supported yet; using first one");
		return (SRVRecord)records[0];
	}


    // data classes

    @RequiredArgsConstructor
    @ToString(exclude="logs")
    public static class Configuration implements Serializable {

        public final String userName, password;
        public final boolean preemptive;

        public final ServiceInfo cardDAV;
        public final ServiceInfo calDAV;

        public final String logs;


        @ToString
        public static class ServiceInfo implements Serializable {
            @Getter
            HttpUrl principal;

            @Getter
            final Set<HttpUrl> homeSets = new HashSet<>();

            @Getter
            final Map<HttpUrl, CollectionInfo> collections = new HashMap<>();
        }
    }

}