package com.notes.nicefact.to;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.notes.nicefact.entity.Institute;
import com.notes.nicefact.entity.InstituteMember;
import com.notes.nicefact.enums.InstituteType;
import com.notes.nicefact.enums.LANGUAGE;
import com.notes.nicefact.enums.SHARING;
import com.notes.nicefact.enums.UserPosition;
import com.notes.nicefact.util.Constants;
import com.notes.nicefact.util.CurrentContext;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InstituteTO {
	
	List<GroupMemberTO> members = new ArrayList<>();

	List<TagTO> tags = new ArrayList<>();
	private SHARING sharing;
	
	Integer noOfMembers;

	Integer noOfAdmins;

	private String name;

	private String icon;

	private long id;

	boolean isAdmin;
	boolean isMember;
	boolean isBlocked;

	private String bgImageId;
	
	private String bgImagePath;
	
	FileTO bgImageFile ;

	String folderId;

	long lastModified;

	long created;

	String description;

	List<LANGUAGE> languages = new ArrayList<>();

	Set<String> admins = new HashSet<>();
	
	InstituteType type;

	private Set<UserPosition> positions = new HashSet<>();
	boolean isJoinRequestApproved ;
	boolean isJoinRequested ;
	
	public InstituteTO() {
		
	}
	public InstituteTO(InstituteMember member) {
		this(member.getInstitute());
		this.isJoinRequestApproved = member.getIsJoinRequestApproved();
		if(this.isJoinRequestApproved == false){
			this.isJoinRequested = true;
		}
		this.positions = member.getPositions();
	}

	public InstituteTO(Institute group ) {
		this.id = group.getId();
		this.noOfMembers = group.getNoOfMembers();
		this.noOfAdmins = group.getNoOfAdmins();
		this.name = group.getName();
		this.bgImageId = group.getBgImageId();
		this.lastModified = group.getUpdatedTime().getTime();
		this.created = group.getCreatedTime().getTime();
		this.description = group.getDescription();
		admins = group.getAdmins();
		this.type = group.getType();
		if(group.getBgImagePath()!=null){
			try {
				this.bgImagePath = Base64.encodeBase64URLSafeString(group.getBgImagePath().getBytes(Constants.UTF_8));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		if (CurrentContext.getAppUser() != null) {
			isJoinRequestApproved = CurrentContext.getAppUser().getInstituteIds().contains(id);
			isAdmin = group.getAdmins().contains(CurrentContext.getEmail());
			isBlocked = group.getBlocked().contains(CurrentContext.getEmail());
			isJoinRequested = CurrentContext.getAppUser().getJoinRequestInstitutes().contains(id);
		}
	}

	public InstituteType getType() {
		return type;
	}

	public void setType(InstituteType type) {
		this.type = type;
	}

	public boolean getIsBlocked() {
		return isBlocked;
	}

	public void setIsBlocked(boolean isBlocked) {
		this.isBlocked = isBlocked;
	}

	public Integer getNoOfAdmins() {
		return noOfAdmins;
	}

	public void setNoOfAdmins(Integer noOfAdmins) {
		this.noOfAdmins = noOfAdmins;
	}

	public Integer getNoOfMembers() {
		return noOfMembers;
	}

	public void setNoOfMembers(Integer noOfMembers) {
		this.noOfMembers = noOfMembers;
	}

	public List<TagTO> getTags() {
		return tags;
	}

	public void setTags(List<TagTO> tags) {
		this.tags = tags;
	}

	public boolean getIsAdmin() {
		return isAdmin;
	}

	public void setAdmin(boolean isAdmin) {
		this.isAdmin = isAdmin;
	}

	public String getFolderId() {
		return folderId;
	}

	public void setFolderId(String folderId) {
		this.folderId = folderId;
	}

	public Set<String> getAdmins() {
		return admins;
	}

	public void setAdmins(Set<String> admins) {
		this.admins = admins;
	}

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public List<LANGUAGE> getLanguages() {
		return languages;
	}

	public void setLanguages(List<LANGUAGE> languages) {
		this.languages = languages;
	}

	public long getCreated() {
		return created;
	}

	public void setCreated(long created) {
		this.created = created;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public long getLastModified() {
		return lastModified;
	}

	public void setLastModified(long lastModified) {
		this.lastModified = lastModified;
	}

	public SHARING getSharing() {
		return sharing;
	}

	public void setSharing(SHARING sharing) {
		this.sharing = sharing;
	}


	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public String getBgImageId() {
		return bgImageId;
	}

	public void setBgImageId(String bgImageId) {
		this.bgImageId = bgImageId;
	}

	public boolean getIsMember() {
		return isMember;
	}

	public void setIsMember(boolean isMember) {
		this.isMember = isMember;
	}

	public List<GroupMemberTO> getMembers() {
		return members;
	}

	public void setMembers(List<GroupMemberTO> members) {
		this.members = members;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Set<UserPosition> getPositions() {
		return positions;
	}

	public void setPositions(Set<UserPosition> positions) {
		this.positions = positions;
	}
	public FileTO getBgImageFile() {
		return bgImageFile;
	}
	public void setBgImageFile(FileTO bgImageFile) {
		this.bgImageFile = bgImageFile;
	}
	public String getBgImagePath() {
		return bgImagePath;
	}
	public void setBgImagePath(String bgImagePath) {
		this.bgImagePath = bgImagePath;
	}
	public boolean getIsJoinRequestApproved() {
		return isJoinRequestApproved;
	}
	public void setIsJoinRequestApproved(boolean isJoinRequestApproved) {
		this.isJoinRequestApproved = isJoinRequestApproved;
	}
	public boolean getIsJoinRequested() {
		return isJoinRequested;
	}
	public void setIsJoinRequested(boolean isJoinRequested) {
		this.isJoinRequested = isJoinRequested;
	}

}
