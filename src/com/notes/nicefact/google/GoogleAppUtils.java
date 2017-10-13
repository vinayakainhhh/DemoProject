package com.notes.nicefact.google;

import java.io.IOException;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.drive.Drive;
import com.notes.nicefact.entity.AppUser;
import com.notes.nicefact.util.AppProperties;
import com.notes.nicefact.util.CurrentContext;

public class GoogleAppUtils {

	private static final JsonFactory JSON_FACTORY =   JacksonFactory.getDefaultInstance();

    private static HttpTransport HTTP_TRANSPORT;
 
	private static  GoogleCredential credential;
	 
	private static Drive driveService;
	private static Calendar calendarService;

    static {
        try {
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
          
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

  
    public static Credential getCredential() throws IOException {
    	if(credential==null){
    		AppProperties	appProperties=	AppProperties.getInstance();
    		 credential = new GoogleCredential.Builder()
	            .setTransport(HTTP_TRANSPORT)
	            .setJsonFactory(JSON_FACTORY)
	            .setClientSecrets(appProperties.getGoogleClientId(),appProperties.getGoogleClientSecret() ).build();
    	}
	    
	     return credential;
    }    
    
    public static com.google.api.services.drive.Drive    getDriveService() throws IOException {
    	AppUser user = CurrentContext.getAppUser();
    	if(user.getUseGoogleDrive()){    	
    		if(driveService==null){
    			driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(AppProperties.getInstance().getAppName())
                .build();
    		}
    		return driveService;
    	}
		return null;
    }
    
    public static com.google.api.services.calendar.Calendar    getCalendarService() throws IOException {
    	AppUser user = CurrentContext.getAppUser();
    	if(user.getUseGoogleCalendar()){  	
    		if(calendarService==null){
    			calendarService = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(AppProperties.getInstance().getAppName())
                .build();
    		}
    		return calendarService;
    	}
		return null;
    }
}
