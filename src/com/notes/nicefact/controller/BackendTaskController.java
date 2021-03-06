package com.notes.nicefact.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.AsyncInvoker;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.log4j.Logger;

import com.notes.nicefact.dao.TaskSubmissionDAO;
import com.notes.nicefact.entity.AbstractFile;
import com.notes.nicefact.entity.AbstractFile.UPLOAD_TYPE;
import com.notes.nicefact.entity.AppUser;
import com.notes.nicefact.entity.BackendTask;
import com.notes.nicefact.entity.BackendTask.BackendTaskStatus;
import com.notes.nicefact.entity.CommentRecipient;
import com.notes.nicefact.entity.Group;
import com.notes.nicefact.entity.GroupMember;
import com.notes.nicefact.entity.Institute;
import com.notes.nicefact.entity.InstituteMember;
import com.notes.nicefact.entity.Notification;
import com.notes.nicefact.entity.NotificationRecipient;
import com.notes.nicefact.entity.Post;
import com.notes.nicefact.entity.PostComment;
import com.notes.nicefact.entity.PostFile;
import com.notes.nicefact.entity.PostRecipient;
import com.notes.nicefact.entity.TaskSubmission;
import com.notes.nicefact.entity.TaskSubmissionFile;
import com.notes.nicefact.entity.Tutorial;
import com.notes.nicefact.entity.TutorialFile;
import com.notes.nicefact.enums.NotificationAction;
import com.notes.nicefact.enums.NotificationType;
import com.notes.nicefact.service.AppUserService;
import com.notes.nicefact.service.BackendTaskService;
import com.notes.nicefact.service.CommonEntityService;
import com.notes.nicefact.service.GoogleDriveService;
import com.notes.nicefact.service.GoogleDriveService.FOLDER;
import com.notes.nicefact.service.GroupService;
import com.notes.nicefact.service.InstituteService;
import com.notes.nicefact.service.NotificationService;
import com.notes.nicefact.service.PostService;
import com.notes.nicefact.service.PushService;
import com.notes.nicefact.service.ScheduleService;
import com.notes.nicefact.service.TaskService;
import com.notes.nicefact.service.TutorialService;
import com.notes.nicefact.to.CommentTO;
import com.notes.nicefact.to.FileTO;
import com.notes.nicefact.to.GoogleDriveFile;
import com.notes.nicefact.to.MoveFileTO;
import com.notes.nicefact.to.NotificationTO;
import com.notes.nicefact.to.SearchTO;
import com.notes.nicefact.util.AppProperties;
import com.notes.nicefact.util.CacheUtils;
import com.notes.nicefact.util.Constants;
import com.notes.nicefact.util.EntityManagerHelper;
import com.notes.nicefact.util.MailService;
import com.notes.nicefact.util.Utils;

@Path("/backend")
public class BackendTaskController extends CommonController {

	static Logger logger = Logger.getLogger(BackendTaskController.class.getSimpleName());

	@POST
	@Path("post/addThumbnail")
	public void addThumbnailToPostFiles(@QueryParam("postId") Long postId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("addThumbnailToPostFiles, postId : " + postId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);
			Post post = postService.get(postId);
			AppUser user = CacheUtils.getAppUser(post.getCreatedBy());
			if (user.getUseGoogleDrive()) {
				moveGroupPostFilesToUserGoogleDrive(post, user, em);
			} else {
				generateGroupPostFileThumbnail(em, post);
			}
			Utils.sendPostToUI(post);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit addThumbnailToPostFiles");
		renderResponseRaw(true, response);
	}

	private void moveGroupPostFilesToUserGoogleDrive(Post post, AppUser user, EntityManager em) {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<PostFile> files = post.getFiles();
		GoogleDriveFile driveFile;
		MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).setGroupId(post.getGroupId()).addParents( FOLDER.Attachments, FOLDER.Post).setUser(user);
		for (PostFile postFile : files) {
			if (StringUtils.isBlank(postFile.getGoogleDriveId())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				try {
					driveFile = driveService.uploadFileToUserAccount(postFile, user);
					if (null != driveFile) {
						moveFileTO.addFileIds(driveFile.getId());
						driveService.renameFile(driveFile.getId(), postFile.getName(), user);
						postFile.setGoogleDriveId(driveFile.getId());
						postFile.setIcon(driveFile.getIconLink());
						postFile.setDriveLink(driveFile.getEditLink());
						postFile.setEmbedLink(driveFile.getEmbedLink());
						postFile.setUploadType(UPLOAD_TYPE.GOOGLE_DRIVE);
						commonService.upsert(postFile);
						try {
							Files.deleteIfExists(Paths.get(postFile.getPath()));
						} catch (IOException e) {
							logger.error("could not delete : " + postFile.getPath() + " , " + e.getMessage(), e);
						}
						getGroupPostFilesThumbnailFromDriveFile(driveFile, post.getGroupId(), postFile, user, commonService);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			} else if (StringUtils.isBlank(postFile.getThumbnail())) {
				driveFile = driveService.getFileFields(postFile.getGoogleDriveId(), "thumbnailLink", user);
				getGroupPostFilesThumbnailFromDriveFile(driveFile,post.getGroupId(), postFile, user, commonService);
			}else {
				logger.info("File is already on google drive , " + postFile.getName() + " , " + postFile.getMimeType());
			}
		}
		driveService.moveFile(moveFileTO);
	}
	
	void getGroupPostFilesThumbnailFromDriveFile(GoogleDriveFile driveFile, long groupId, AbstractFile postFile, AppUser user, CommonEntityService commonService ) {
		if (null != driveFile && StringUtils.isNotBlank(driveFile.getThumbnailLink())) {
			GoogleDriveService driveService = GoogleDriveService.getInstance();
			try {
				HttpResponse httpResponse = driveService.doGet(driveFile.getThumbnailLink(), null, user);
				if (null != httpResponse && httpResponse.getStatusLine().getStatusCode() == 200 && httpResponse.getEntity() != null) {
					/*
					 * save thumbnail file in local storage and
					 * udpate database
					 */
					byte[] thumbnailBytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
					FileTO fileTo = Utils.writeGroupPostFileThumbnail(thumbnailBytes,groupId, postFile.getName());
					postFile.setThumbnail(fileTo.getServerName());
					commonService.upsert(postFile);
				} 
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

	private void generateGroupPostFileThumbnail(EntityManager em, Post post) throws InterruptedException, IOException {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<PostFile> files = post.getFiles();
		GoogleDriveFile driveFile;
		List<GoogleDriveFile> driveFiles = new ArrayList<>();
		for (PostFile postFile : files) {
			if (StringUtils.isBlank(postFile.getThumbnail())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				driveFile = driveService.uploadFileToServiceAccount(postFile);
				if (null != driveFile) {
					driveService.moveFileServiceAccount(driveFile.getId(), AppProperties.getInstance().getDriveThumbnailFolderId());
					driveFile.setServerPath(postFile.getPath());
					driveFiles.add(driveFile);
					postFile.setTempGoogleDriveId(driveFile.getId());
					commonService.upsert(postFile);
				}
			}
		}

		Thread.sleep(60000);
		/* download thumbnail links and update postfile in db */

		for (GoogleDriveFile googleDriveFile : driveFiles) {
			if (StringUtils.isBlank(googleDriveFile.getThumbnailLink())) {
				driveFile = driveService.getFileFieldsServiceAccount(googleDriveFile.getId(), "thumbnailLink");
				if (driveFile != null && StringUtils.isNotBlank(driveFile.getThumbnailLink())) {
					googleDriveFile.setThumbnailLink(driveFile.getThumbnailLink());
				}
			}

			if (StringUtils.isBlank(googleDriveFile.getThumbnailLink())) {
				logger.info("failed to get thumbnail for : " + googleDriveFile.getMimeType() + " , " + googleDriveFile.getServerPath() + " , " + googleDriveFile.getTitle());
			} else {
				for (PostFile postFile : files) {
					if (StringUtils.isNotBlank(postFile.getPath()) && postFile.getPath().equals(googleDriveFile.getServerPath())) {
						HttpResponse httpResponse = driveService.makeServiceAccountGetRequest(googleDriveFile.getThumbnailLink(), null);
						if (null != httpResponse && httpResponse.getStatusLine().getStatusCode() == 200 && httpResponse.getEntity() != null) {
							byte[] thumbnailBytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
							FileTO fileTo = Utils.writeGroupPostFileThumbnail(thumbnailBytes, post.getGroupId(), postFile.getName());

							postFile.setThumbnail(fileTo.getServerName());
							postFile.setTempGoogleDriveId(null);
							commonService.upsert(postFile);
							driveService.deleteFileServiceAccount(googleDriveFile.getId());
						}
						break;
					}
				}

			}
		}
	}

	@POST
	@Path("tutorial/saveTutorialTask")
	public void saveTutorialTask(@QueryParam("tutorialId") Long tutorialId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("saveTutorialTask, tutorialId : " + tutorialId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TutorialService tutorialService = new TutorialService(em);
			Tutorial tutorial = tutorialService.get(tutorialId);
			AppUser user = CacheUtils.getAppUser(tutorial.getCreatedBy());
			if (user.getUseGoogleDrive() ) {
				moveTutorialFilesToUserGoogleDrive(tutorial, user, em);
			} else {
				generateTutorialFileThumbnails(em, tutorial);
			}

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit saveTutorialTask");
		renderResponseRaw(true, response);
	}

	private void moveTutorialFilesToUserGoogleDrive(Tutorial tutorial, AppUser user, EntityManager em) {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<TutorialFile> files = tutorial.getFiles();
		GoogleDriveFile driveFile;
		MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).addParents( FOLDER.Tutorial).setUser(user);
		for (TutorialFile postFile : files) {
			if (StringUtils.isBlank(postFile.getGoogleDriveId())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				try {
					driveFile = driveService.uploadFileToUserAccount(postFile, user);
					if (null != driveFile) {
						moveFileTO.addFileIds(driveFile.getId());
						driveService.renameFile(driveFile.getId(), postFile.getName(), user);
						postFile.setGoogleDriveId(driveFile.getId());
						postFile.setIcon(driveFile.getIconLink());
						postFile.setDriveLink(driveFile.getEditLink());
						postFile.setEmbedLink(driveFile.getEmbedLink());
						postFile.setUploadType(UPLOAD_TYPE.GOOGLE_DRIVE);
						commonService.upsert(postFile);
						try {
							Files.deleteIfExists(Paths.get(postFile.getPath()));
						} catch (IOException e) {
							logger.error("could not delete : " + postFile.getPath() + " , " + e.getMessage(), e);
						}

						getTutorialFilesThumbnailFromDriveFile(driveFile, postFile, user, commonService);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			} else if (StringUtils.isBlank(postFile.getThumbnail())) {
				driveFile = driveService.getFileFields(postFile.getGoogleDriveId(), "thumbnailLink", user);
				getTutorialFilesThumbnailFromDriveFile(driveFile, postFile, user, commonService);
			} else {
				logger.info("File is already on google drive , " + postFile.getName() + " , " + postFile.getMimeType());
			}
		}
		driveService.moveFile(moveFileTO);
	}

	void getTutorialFilesThumbnailFromDriveFile(GoogleDriveFile driveFile, TutorialFile postFile, AppUser user, CommonEntityService commonService ) {
		if (null != driveFile && StringUtils.isNotBlank(driveFile.getThumbnailLink())) {
			GoogleDriveService driveService = GoogleDriveService.getInstance();
			try {
				HttpResponse httpResponse = driveService.doGet(driveFile.getThumbnailLink(), null, user);
				if (null != httpResponse && httpResponse.getStatusLine().getStatusCode() == 200 && httpResponse.getEntity() != null) {
					/*
					 * save thumbnail file in local storage and
					 * udpate database
					 */
					byte[] thumbnailBytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
					FileTO fileTo = Utils.writeTutorialFileThumbnail(thumbnailBytes, postFile.getCreatedBy(), postFile.getName());
					postFile.setThumbnail(fileTo.getServerName());
					commonService.upsert(postFile);
				} 
			} catch (Exception e) {
				logger.error(e.getMessage(), e);
			}
		}
	}
	
	private void generateTutorialFileThumbnails(EntityManager em, Tutorial tutorial) throws InterruptedException, IOException {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<TutorialFile> files = tutorial.getFiles();
		GoogleDriveFile driveFile;
		List<GoogleDriveFile> driveFiles = new ArrayList<>();
		for (TutorialFile postFile : files) {
			if (StringUtils.isBlank(postFile.getThumbnail())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				driveFile = driveService.uploadFileToServiceAccount(postFile);
				if (null != driveFile) {
					driveService.moveFileServiceAccount(driveFile.getId(), AppProperties.getInstance().getDriveThumbnailFolderId());
					driveFile.setServerPath(postFile.getPath());
					driveFiles.add(driveFile);
					postFile.setTempGoogleDriveId(driveFile.getId());
					commonService.upsert(postFile);
				}
			}
		}

		Thread.sleep(60000);
		/* download thumbnail links and update postfile in db */

		for (GoogleDriveFile googleDriveFile : driveFiles) {
			if (StringUtils.isBlank(googleDriveFile.getThumbnailLink())) {
				driveFile = driveService.getFileFieldsServiceAccount(googleDriveFile.getId(), "thumbnailLink");
				if (driveFile != null && StringUtils.isNotBlank(driveFile.getThumbnailLink())) {
					googleDriveFile.setThumbnailLink(driveFile.getThumbnailLink());
				}
			}

			if (StringUtils.isBlank(googleDriveFile.getThumbnailLink())) {
				logger.info("failed to get thumbnail for : " + googleDriveFile.getMimeType() + " , " + googleDriveFile.getServerPath() + " , " + googleDriveFile.getTitle());
			} else {
				for (TutorialFile postFile : files) {
					if (StringUtils.isNotBlank(postFile.getPath()) && postFile.getPath().equals(googleDriveFile.getServerPath())) {
						HttpResponse httpResponse = driveService.makeServiceAccountGetRequest(googleDriveFile.getThumbnailLink(), null);
						if (null != httpResponse && httpResponse.getStatusLine().getStatusCode() == 200 && httpResponse.getEntity() != null) {
							byte[] thumbnailBytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
							FileTO fileTo = Utils.writeTutorialFileThumbnail(thumbnailBytes, tutorial.getCreatedBy(), postFile.getName());
							postFile.setThumbnail(fileTo.getServerName());
							postFile.setTempGoogleDriveId(null);
							commonService.upsert(postFile);
							driveService.deleteFileServiceAccount(googleDriveFile.getId());
						}
						break;
					}
				}

			}
		}
	}

	@POST
	@Path("post/generateGroupPostCreatedNotification")
	public void generateGroupPostCreatedNotification(@QueryParam("postId") Long postId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start generateGroupPostCreatedNotification, postId : " + postId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);
			NotificationService notificationService = new NotificationService(em);
			Post post = postService.get(postId);
			AppUser sender = CacheUtils.getAppUser(post.getCreatedBy());
			AppUser user;
			NotificationRecipient notificationRecipient;
			/* tagged notification for people tagged in post */
			Set<String> recipientEmailSet = new HashSet<>();
			/*
			 * add person who created post so that he does not get notification
			 */
			recipientEmailSet.add(post.getCreatedBy());
			Notification notification = new Notification(post, sender);
			notificationService.upsert(notification);
			if (!post.getRecipients().isEmpty()) {
				for (PostRecipient recipient : post.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostMentionEmail());
						}
						notificationRecipient.setAction(NotificationAction.POST_MENTIONED);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			/* notification for members of group */
			Group group = CacheUtils.getGroup(post.getGroupId());
			if (group != null && group.getMembers() != null) {
				for (GroupMember member : group.getMembers()) {
					if (recipientEmailSet.add(member.getEmail())) {
						user = CacheUtils.getAppUser(member.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(member.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostEmail());
						}
						notificationRecipient.setAction(NotificationAction.POSTED_GROUP);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			if (notification.getRecipients().isEmpty()) {
				notificationService.remove(notification);
			} else {
				notificationService.upsert(notification);
				BackendTaskService backendTaskService = new BackendTaskService(em);
				backendTaskService.createSendNotificationMailsTask(notification);
				backendTaskService.createSendPushNotificationTask(notification);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit generateGroupPostCreatedNotification");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("post/generateCommentedNotification")
	public void generatePostCommentedNotification(@QueryParam("postId") Long postId, @QueryParam("commentId") Long commentId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start generatePostCommentedNotification, postId : " + postId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);
			NotificationService notificationService = new NotificationService(em);
			Post post = postService.get(postId);
			PostComment comment = postService.getPostCommentById(commentId);
			AppUser postSender = CacheUtils.getAppUser(post.getCreatedBy());
			AppUser user;
			NotificationRecipient notificationRecipient;
			Set<String> recipientEmailSet = new HashSet<>();
			/* add person who created comment to avoid sending notification */
			recipientEmailSet.add(comment.getCreatedBy());

			Notification notification = new Notification(post, comment, postSender);
			notificationService.upsert(notification);

			/* check and create notification for post creator */
			if (recipientEmailSet.add(post.getCreatedBy())) {
				notificationRecipient = new NotificationRecipient(postSender);
				notificationRecipient.setSendEmail(postSender.getSendPostCommentedEmail());
				notificationRecipient.setAction(NotificationAction.COMMENTED_SENDER);
				notificationRecipient.setNotification(notification);
				notification.getRecipients().add(notificationRecipient);
				notificationService.upsertRecipient(notificationRecipient);
			}
			/* notification for people tagged in this comment */
			if (!comment.getRecipients().isEmpty()) {

				for (CommentRecipient recipient : comment.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentMentiondEmail());
						}
						notificationRecipient.setAction(NotificationAction.COMMENT_MENTIONED);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			/* notify people tagged in original post that someone commented */
			if (!post.getRecipients().isEmpty()) {
				for (PostRecipient recipient : post.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentOnMentiondPostEmail());
						}
						notificationRecipient.setAction(NotificationAction.COMMENTED_MENTIONED_POST);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			if (notification.getRecipients().isEmpty()) {
				notificationService.remove(notification);
			} else {
				notificationService.upsert(notification);
				BackendTaskService backendTaskService = new BackendTaskService(em);
				backendTaskService.createSendNotificationMailsTask(notification);
				backendTaskService.createSendPushNotificationTask(notification);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit generatePostCommentedNotification");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("post/generateCommentRepliedNotification")
	public void generatePostCommentRepliedNotification(@QueryParam("postId") Long postId, @QueryParam("commentId") Long commentId, @QueryParam("commentReplyId") Long commentReplyId, @Context HttpServletResponse response)
			throws IOException, InterruptedException {
		logger.info("start generatePostCommentRepliedNotification, postId : " + postId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);
			NotificationService notificationService = new NotificationService(em);
			Post post = postService.get(postId);
			PostComment comment = postService.getPostCommentById(commentId);
			PostComment commentReply = postService.getPostCommentById(commentReplyId);
			AppUser commentSender = CacheUtils.getAppUser(comment.getCreatedBy());
			AppUser postSender = CacheUtils.getAppUser(post.getCreatedBy());
			AppUser user;
			NotificationRecipient notificationRecipient;
			Set<String> recipientEmailSet = new HashSet<>();

			/*
			 * add person who created comment reply to avoid sending
			 * notification
			 */
			recipientEmailSet.add(commentReply.getCreatedBy());

			Notification notification = new Notification(post, commentReply, commentSender);
			notificationService.upsert(notification);

			/* check and create notification for post parent creator */
			if (recipientEmailSet.add(commentSender.getEmail())) {
				notificationRecipient = new NotificationRecipient(commentSender);
				notificationRecipient.setAction(NotificationAction.REPLIED_SENDER);
				notificationRecipient.setSendEmail(commentSender.getSendCommentReplyEmail());
				notificationRecipient.setNotification(notification);
				notification.getRecipients().add(notificationRecipient);
				notificationService.upsertRecipient(notificationRecipient);
			}

			/* check and create notification for post creator */
			if (recipientEmailSet.add(postSender.getEmail())) {
				notificationRecipient = new NotificationRecipient(postSender);
				notificationRecipient.setSendEmail(postSender.getSendPostCommentedEmail());
				notificationRecipient.setAction(NotificationAction.COMMENTED_SENDER);
				notificationRecipient.setNotification(notification);
				notification.getRecipients().add(notificationRecipient);
				notificationService.upsertRecipient(notificationRecipient);
			}
			/* notification for people tagged in this comment */
			if (!commentReply.getRecipients().isEmpty()) {
				for (CommentRecipient recipient : commentReply.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentMentiondEmail());
						}
						notificationRecipient.setAction(NotificationAction.COMMENT_REPLY_MENTIONED);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			/* notification for people tagged in parent comment */
			if (!comment.getRecipients().isEmpty()) {
				for (CommentRecipient recipient : comment.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentOnCommentEmail());
						}
						notificationRecipient.setAction(NotificationAction.REPLIED_MENTIONED_COMMENT);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			/*
			 * send notifications to users who replied to parent comment or are
			 * mentioned in other replies to parent comment
			 */
			for (PostComment thisCommentReply : comment.getComments()) {
				if (commentReply.getId() != thisCommentReply.getId()) {
					if (recipientEmailSet.add(thisCommentReply.getCreatedBy())) {
						user = CacheUtils.getAppUser(thisCommentReply.getCreatedBy());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(thisCommentReply.getCreatedBy());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentOnCommentEmail());
						}
						notificationRecipient.setAction(NotificationAction.COMMENT_FOLLOWING);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}

					for (CommentRecipient recipient : thisCommentReply.getRecipients()) {
						if (recipientEmailSet.add(recipient.getEmail())) {
							user = CacheUtils.getAppUser(recipient.getEmail());
							if (user == null) {
								notificationRecipient = new NotificationRecipient(recipient.getEmail());
							} else {
								notificationRecipient = new NotificationRecipient(user);
								notificationRecipient.setSendEmail(user.getSendCommentOnCommentEmail());
							}
							notificationRecipient.setAction(NotificationAction.COMMENT_FOLLOWING);
							notificationRecipient.setNotification(notification);
							notification.getRecipients().add(notificationRecipient);
							notificationService.upsertRecipient(notificationRecipient);
						}
					}

				}
			}

			/* notify people tagged in original post that someone commented */
			if (!post.getRecipients().isEmpty()) {
				for (PostRecipient recipient : post.getRecipients()) {
					if (recipientEmailSet.add(recipient.getEmail())) {
						user = CacheUtils.getAppUser(recipient.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(recipient.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendCommentOnMentiondPostEmail());
						}
						notificationRecipient.setAction(NotificationAction.COMMENTED_MENTIONED_POST);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			if (notification.getRecipients().isEmpty()) {
				notificationService.remove(notification);
			} else {
				notificationService.upsert(notification);
				BackendTaskService backendTaskService = new BackendTaskService(em);
				backendTaskService.createSendNotificationMailsTask(notification);
				backendTaskService.createSendPushNotificationTask(notification);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit generatePostCommentRepliedNotification");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("post/sendNotificationMails")
	public void sendNotificationMails(@QueryParam("notificationId") Long notificationId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("sendNotificationMails, notificationId : " + notificationId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);

			NotificationService notificationService = new NotificationService(em);
			MailService mailService = MailService.getInstance();
			Notification notification = notificationService.get(notificationId);
			if (null != notification) {
				if (null != notification.getEntityId()) {
					/* notification for posts */
					Post post = postService.get(notification.getEntityId());
					if (null != post) {
						PostComment comment = null;
						if (null != notification.getSubEntityId()) {
							comment = postService.getPostCommentById(notification.getSubEntityId());
						}
						for (NotificationRecipient recipient : notification.getRecipients()) {
							if (recipient.getSendEmail() && Utils.isValidEmailAddress(recipient.getEmail())) {
								mailService.sendPostNotificationEmail(post, comment, notification, recipient);

							}
						}
					}
				} else if (null != notification.getGroupId()) {
					for (NotificationRecipient recipient : notification.getRecipients()) {
						if (recipient.getSendEmail() && Utils.isValidEmailAddress(recipient.getEmail())) {
							mailService.sendGroupAddNotificationnEmail(notification, recipient);

						}
					}
				}else if (null != notification.getInstituteId()) {
					for (NotificationRecipient recipient : notification.getRecipients()) {
						if (recipient.getSendEmail() && Utils.isValidEmailAddress(recipient.getEmail())) {
							mailService.sendInstituteAddNotificationnEmail(notification, recipient);

						}
					}
				}

			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit sendNotificationMails");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("sendWelcomeMail")
	public void sendWelcomeMail(@QueryParam("email") String email, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("sendWelcomeMail, email : " + email);
		try {
			AppUser user = CacheUtils.getAppUser(email);
			if (null != user) {
				MailService.getInstance().sendWelcomeMail(user);
			}

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
		}

		logger.info("exit sendWelcomeMail");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("sendVerifyMail")
	public void sendVerifyMail(@QueryParam("email") String email, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("sendVerifyMail, email : " + email);
		try {
			AppUser user = CacheUtils.getAppUser(email);
			if (null != user) {
				MailService.getInstance().sendVerifyEmailMail(user);
			}

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
		}

		logger.info("exit sendVerifyMail");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("user/firstLogin")
	public void firstLogin(@QueryParam("email") String email, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("firstLogin, email : " + email);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			GroupService groupService = new GroupService(em);

			groupService.udpateAppUserAccesPermissions(email);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit firstLogin");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("markNotificationAsRead")
	public void markNotificationAsRead(@QueryParam("email") String email, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("markNotificationAsRead, email : " + email);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {

			NotificationService notificationService = new NotificationService(em);
			List<NotificationRecipient> recipients = notificationService.getAllUnreadRecipientsByEmail(email);
			logger.info("recipients len : " + recipients.size());
			for (NotificationRecipient recipient : recipients) {
				recipient.setIsRead(true);
				notificationService.upsertRecipient(recipient);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit markNotificationAsRead");
		renderResponseRaw(true, response);
	}

	public void updateGroupMemberAccessPermissions( Long groupId) {
		logger.info("start updateGroupMemberAccessPermissions, groupId : " + groupId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			GroupService groupService = new GroupService(em);
			Group group = groupService.get(groupId);
			ScheduleService scheduleService = new ScheduleService(em);
			if (null != group) {
				groupService.updateGroupMemberAccessPermissions(group);
				scheduleService.updateGroupMemberPermissionsOnCalendar(groupId);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit updateGroupMemberAccessPermissions");
	}
	

	@POST
	@Path("group/afterSave")
	public void afterGroupSave(@QueryParam("groupId") Long groupId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start afterGroupSave, groupId : " + groupId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			GroupService groupService = new GroupService(em);
			Group group = groupService.get(groupId);
			ScheduleService scheduleService = new ScheduleService(em);
			if (null != group) {
				groupService.updateGroupMemberAccessPermissions(group);
				groupService.updateGroupFolderPermissions(groupId);
				scheduleService.updateGroupMemberPermissionsOnCalendar(groupId);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit afterGroupSave");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("group/sendGroupAddNotification")
	public void sendGroupAddNotification(@QueryParam("groupId") Long groupId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start sendGroupAddNotification, groupId : " + groupId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			GroupService groupService = new GroupService(em);
			NotificationService notificationService = new NotificationService(em);
			Group group = groupService.get(groupId);
			AppUser notificationSender = null;

			AppUser user = null;
			if (null != group) {
				Set<String> recipientEmailSet = new HashSet<>();

				for (GroupMember member : group.getMembers()) {
					if (!member.getIsNotificationSent()) {
						if (null == notificationSender) {
							notificationSender = CacheUtils.getAppUser(member.getCreatedBy());
						}
						recipientEmailSet.add(member.getEmail());
					}
				}

				/* avoid sending mail to group creator */
				recipientEmailSet.remove(group.getCreatedBy());

				if (!recipientEmailSet.isEmpty()) {
					Notification notification = new Notification(notificationSender);
					notification.setGroupId(group.getId());
					notification.setGroupName(group.getName());
					notification.setTitle(group.getName());
					notification.setType(NotificationType.GROUP);
					notificationService.upsert(notification);
					NotificationRecipient notificationRecipient;
					for (String email : recipientEmailSet) {
						user = CacheUtils.getAppUser(email);
						if (user == null) {
							notificationRecipient = new NotificationRecipient(email);
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostMentionEmail());
						}
						notificationRecipient.setAction(NotificationAction.GROUP_ADDED);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
					notificationService.upsert(notification);

					/* update flag in group memeber */
					for (GroupMember member : group.getMembers()) {
						if (!member.getIsNotificationSent()) {
							member.setIsNotificationSent(true);
							groupService.updateGroupMember(member);
						}
					}

					BackendTaskService backendTaskService = new BackendTaskService(em);
					backendTaskService.createSendNotificationMailsTask(notification);
					backendTaskService.createSendPushNotificationTask(notification);
				}

			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit sendGroupAddNotification");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("user/createGoogleDriveFolder")
	public void createGoogleDriveFolderForUserTask(@QueryParam("email") String email, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start createGoogleDriveFolderForUserTask, email : " + email);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			AppUserService appUserService = new AppUserService(em);
			GoogleDriveService googleDriveService = GoogleDriveService.getInstance();
			AppUser user = appUserService.getAppUserByEmail(email);
			if (StringUtils.isNotBlank(user.getRefreshToken())) {
				MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).setTest().addParents( FOLDER.Attachments,  FOLDER.Library, FOLDER.Task_Submission).setUser(user);
				googleDriveService.moveFile(moveFileTO);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit createGoogleDriveFolderForUserTask");
		renderResponseRaw(true, response);
	}

	@GET
	@Path("manuallyTrigger")
	public void runManually(@QueryParam("taskId") Long taskId, @Context HttpServletResponse response) throws IOException {
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		String msg = "Task not found";
		try {
			BackendTaskService backendTaskService = new BackendTaskService(em);
			BackendTask task = backendTaskService.get(taskId);
			if (task != null) {
				backendTaskService.triggerBackendTask(task);
			}
			msg = "successfully triggered";
		} catch (Exception e) {
			msg = "failed : " + e.getMessage();
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info(msg);
		renderResponseRaw(msg, response);
	}

	@POST
	@Path("run")
	@Consumes("plain/text")
	public void runAsyncTask(@QueryParam("taskId") Long taskId, @Context HttpServletResponse response1, @Context HttpServletRequest request) {
		logger.info("start runAsyncTask , taskId : " + taskId);
		String path = "";
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			BackendTaskService backendTaskService = new BackendTaskService(em);
			BackendTask task = backendTaskService.get(taskId);
			if (null != task) {
				if (BackendTaskStatus.QUEUED.equals(task.getStatus()) || BackendTaskStatus.FAILED.equals(task.getStatus())) {
					try {
						task.setStatus(BackendTaskStatus.RUNNING);
						task = backendTaskService.upsert(task);
						path = task.getPath();
						Client client = ClientBuilder.newClient();
						WebTarget target = client.target(Constants.LOCALHOST_ADDRESS + "/a/backend/").path(path);
						if (!task.getParamsMap().isEmpty()) {
							for (String key : task.getParamsMap().keySet()) {
								target = target.queryParam(key, task.getParamsMap().get(key));
							}
						}
						AsyncInvoker asyncInvoker = target.request().async();
						final Future<Response> responseFuture = asyncInvoker.post(null);
						logger.info("Request is being processed asynchronously. , path : " + task.getPath());

						// get() waits for the response to be ready
						final Response response = responseFuture.get();
						if (response.getEntity() != null) {
							String resp = response.readEntity(String.class);
							logger.info("status : " + response.getStatus() + " , resp : " + resp);
						} else {
							logger.info("status : " + response.getStatus());
						}

						if (response.getStatus() == 200) {
							task.setStatus(BackendTaskStatus.COMPLETED);
						} else {
							task.setStatus(BackendTaskStatus.FAILED);
						}
						task = backendTaskService.upsert(task);
					} catch (Exception e) {
						logger.error("task : " + task + " , " + e.getMessage(), e);
						task.incrementRetries();
						task.setStatus(BackendTaskStatus.FAILED);
						task = backendTaskService.upsert(task);
					}
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		renderResponseRaw(true, response1);
		logger.info("exit runAsyncTask , path : " + path);
	}

	@POST
	@Path("task/afterSave")
	public void afterTaskSave(@QueryParam("taskId") Long taskId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start afterTaskSave, taskId : " + taskId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TaskService taskService = new TaskService(em);
			NotificationService notificationService = new NotificationService(em);
			Post post = taskService.get(taskId);
			AppUser sender = CacheUtils.getAppUser(post.getCreatedBy());
			AppUser user;
			NotificationRecipient notificationRecipient;
			/* tagged notification for people tagged in post */
			Set<String> recipientEmailSet = new HashSet<>();
			/*
			 * add person who created post so that he does not get notification
			 */
			recipientEmailSet.add(post.getCreatedBy());
			Notification notification = new Notification(post, sender);
			notificationService.upsert(notification);


			/* notification for members of group */
			Group group = CacheUtils.getGroup(post.getGroupId());
			if (group != null && group.getMembers() != null) {
				for (GroupMember member : group.getMembers()) {
					if (recipientEmailSet.add(member.getEmail())) {
						user = CacheUtils.getAppUser(member.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(member.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostEmail());
						}
						notificationRecipient.setAction(NotificationAction.POSTED_GROUP);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			if (notification.getRecipients().isEmpty()) {
				notificationService.remove(notification);
			} else {
				notificationService.upsert(notification);
				BackendTaskService backendTaskService = new BackendTaskService(em);
				backendTaskService.createSendNotificationMailsTask(notification);
				backendTaskService.createSendPushNotificationTask(notification);
			}
			
			if(post.getDeadline() !=null){
				ScheduleService scheduleService = new ScheduleService(em);
				scheduleService.createTaskEvent(post);
			}

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit afterTaskSave");
		renderResponseRaw(true, response);
	}
	
	private void moveTaskFilesToUserGoogleDrive(Post task, AppUser user, EntityManager em) {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<PostFile> files = task.getFiles();
		GoogleDriveFile driveFile;
		MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).setGroupId(task.getGroupId()).addParents( FOLDER.Attachments, FOLDER.Task).setUser(user);
		for (int index = 0 ; index < files.size(); index++) {
			PostFile postFile = files.get(index);
			if (StringUtils.isBlank(postFile.getGoogleDriveId())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				try {
					driveFile = driveService.uploadFileToUserAccount(postFile, user);
					if (null != driveFile) {
						moveFileTO.addFileIds(driveFile.getId());
						driveService.renameFile(driveFile.getId(), postFile.getName(), user);
						postFile.setGoogleDriveId(driveFile.getId());
						postFile.setIcon(driveFile.getIconLink());
						postFile.setDriveLink(driveFile.getEditLink());
						postFile.setEmbedLink(driveFile.getEmbedLink());
						postFile.setUploadType(UPLOAD_TYPE.GOOGLE_DRIVE);
						commonService.upsert(postFile);
						try {
							Files.deleteIfExists(Paths.get(postFile.getPath()));
						} catch (IOException e) {
							logger.error("could not delete : " + postFile.getPath() + " , " + e.getMessage(), e);
						}
						getGroupPostFilesThumbnailFromDriveFile(driveFile, task.getGroupId(), postFile, user, commonService);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			} else if (StringUtils.isBlank(postFile.getThumbnail())) {
				driveFile = driveService.getFileFields(postFile.getGoogleDriveId(), "thumbnailLink", user);
				getGroupPostFilesThumbnailFromDriveFile(driveFile, task.getGroupId(), postFile, user, commonService);
			}else {
				logger.info("File is already on google drive , " + postFile.getName() + " , " + postFile.getMimeType());
			}
		}
		driveService.moveFile(moveFileTO);
	}
	
	
	@POST
	@Path("task/addThumbnail")
	public void addThumbnailToTaskFiles(@QueryParam("taskId") Long taskId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("addThumbnailToTaskFiles, taskId : " + taskId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TaskService taskService = new TaskService(em);
			Post post = taskService.get(taskId);
			AppUser user = CacheUtils.getAppUser(post.getCreatedBy());
			if (user.getUseGoogleDrive() ) {
				moveTaskFilesToUserGoogleDrive(post, user, em);
			}
			Utils.sendPostToUI(post);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit addThumbnailToTaskFiles");
		renderResponseRaw(true, response);
	}

	private void moveTaskSubmissionFilesToUserGoogleDrive(Post task, TaskSubmission submission , AppUser user, EntityManager em) {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<TaskSubmissionFile> files = submission.getFiles();
		GoogleDriveFile driveFile;
		MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).setGroupId(task.getGroupId()).addParents( FOLDER.Task_Submission).setUser(user).setPost(task);
		for (TaskSubmissionFile postFile : files) {
			if (StringUtils.isBlank(postFile.getGoogleDriveId())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				try {
					driveFile = driveService.uploadFileToUserAccount(postFile, user);
					if (null != driveFile) {
						moveFileTO.addFileIds(driveFile.getId());
						driveService.renameFile(driveFile.getId(), postFile.getName(), user);
						postFile.setGoogleDriveId(driveFile.getId());
						postFile.setIcon(driveFile.getIconLink());
						postFile.setDriveLink(driveFile.getEditLink());
						postFile.setEmbedLink(driveFile.getEmbedLink());
						postFile.setUploadType(UPLOAD_TYPE.GOOGLE_DRIVE);
						commonService.upsert(postFile);
						try {
							Files.deleteIfExists(Paths.get(postFile.getPath()));
						} catch (IOException e) {
							logger.error("could not delete : " + postFile.getPath() + " , " + e.getMessage(), e);
						}
						getGroupPostFilesThumbnailFromDriveFile(driveFile,task.getGroupId(), postFile, user, commonService);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			} else if (StringUtils.isBlank(postFile.getThumbnail())) {
				driveFile = driveService.getFileFields(postFile.getGoogleDriveId(), "thumbnailLink", user);
				getGroupPostFilesThumbnailFromDriveFile(driveFile, task.getGroupId(), postFile, user, commonService);
			}else {
				logger.info("File is already on google drive , " + postFile.getName() + " , " + postFile.getMimeType());
			}
		}
		driveService.moveFile(moveFileTO);
	}
	
/*	private void makeTaskGoogleDriveFolder(Post task ,  AppUser user, EntityManager em) {
		Group group = CacheUtils.getGroup(task.getGroupId());
		String name = group.getName() + "-task-" + task.getId();
		GoogleDriveService googleDriveService = GoogleDriveService.getInstance();
		 GoogleDriveFile folder = googleDriveService.createNewFile(name, GoogleFileTypes.FOLDER, user);
		if (null == folder) {
			String msg = "cannot make group task folder for : " + user.getEmail();
			logger.error(msg);
			throw new ServiceException(msg);
		} else {
			googleDriveService.moveFile(folder.getId(), user.getGoogleDriveFolderId(), user);
			task.setGoogleDriveFolderId(folder.getId());
			TaskService taskService = new TaskService(em);
			taskService.upsert(task);

		}
		
	}*/

	@POST
	@Path("task/submission")
	public void afterTaskSubmissionSave(@QueryParam("taskId") Long taskId, @QueryParam("submissionId") Long submissionId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("afterTaskSubmissionSave, taskId : " + taskId + " , submissionId : " + submissionId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TaskService taskService = new TaskService(em);
			TaskSubmissionDAO taskSubmissionDAO = new TaskSubmissionDAO(em);
			
				Post task = taskService.get(taskId);
				TaskSubmission submission = taskSubmissionDAO.get(submissionId);
				if (task == null || submission == null) {
					logger.warn("cannot fetch from db , task : " + task + ", submission : " + submission);
				}
				AppUser user = CacheUtils.getAppUser(task.getCreatedBy());
				if (user.getUseGoogleDrive()  ) {
					moveTaskSubmissionFilesToUserGoogleDrive(task, submission, user, em);
				} else {
					logger.warn("User has not given google permission.");
				}

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit afterTaskSubmissionSave");
		renderResponseRaw(true, response);
	}
	
	@POST
	@Path("institute/afterSave")
	public void institutePostSaveTask(@QueryParam("instituteId") Long instituteId, @Context HttpServletResponse response)  {
		logger.info("start institutePostSaveTask, groupId : " + instituteId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {

			InstituteService instituteService = new InstituteService(em);
			NotificationService notificationService = new NotificationService(em);
			Institute institute = instituteService.get(instituteId);
			AppUser notificationSender = null;

			AppUser user = null;
			if (null != institute) {
				SearchTO searchTO = SearchTO.getInstances().setFirst(0).setLimit(200);
				List<InstituteMember> members = null;
				Set<String> recipientEmailSet = new HashSet<>();
				do {
					members = instituteService.getMembers(instituteId, searchTO);
					for (InstituteMember member : members) {
						if (!member.getIsNotificationSent()) {
							if (null == notificationSender) {
								notificationSender = CacheUtils.getAppUser(member.getCreatedBy());
							}
							recipientEmailSet.add(member.getEmail());
						}
					}
					searchTO.setFirst(searchTO.getFirst() + searchTO.getLimit());
				} while (!members.isEmpty());
				/* avoid sending mail to group creator */
				recipientEmailSet.remove(institute.getCreatedBy());

				if (!recipientEmailSet.isEmpty()) {
					Notification notification = new Notification(notificationSender);
					notification.setInstituteId(institute.getId()).setGroupName(institute.getName()).setTitle(institute.getName()).setType(NotificationType.INSTITUTE);
					notificationService.upsert(notification);
					NotificationRecipient notificationRecipient;
					for (String email : recipientEmailSet) {
						user = CacheUtils.getAppUser(email);
						if (user == null) {
							notificationRecipient = new NotificationRecipient(email);
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostMentionEmail());
						}
						notificationRecipient.setAction(NotificationAction.INSTITUTE_ADDED).setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
					notificationService.upsert(notification);

					/* update flag in group memeber */
					for (InstituteMember member : members) {
						if (!member.getIsNotificationSent()) {
							member.setIsNotificationSent(true);
							instituteService.updateMember(member);
						}
					}

					BackendTaskService backendTaskService = new BackendTaskService(em);
					backendTaskService.createSendNotificationMailsTask(notification);
					backendTaskService.createSendPushNotificationTask(notification);
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit institutePostSaveTask");
		renderResponseRaw(true, response);
	}
	
	@POST
	@Path("schedule/afterSave")
	public void afterScheduleSave(@QueryParam("scheduleId") Long scheduleId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("start afterScheduleSave, scheduleId : " + scheduleId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TaskService taskService = new TaskService(em);
			NotificationService notificationService = new NotificationService(em);
			Post post = taskService.get(scheduleId);
			AppUser sender = CacheUtils.getAppUser(post.getCreatedBy());
			AppUser user;
			NotificationRecipient notificationRecipient;
			/* tagged notification for people tagged in post */
			Set<String> recipientEmailSet = new HashSet<>();
			/*
			 * add person who created post so that he does not get notification
			 */
			recipientEmailSet.add(post.getCreatedBy());
			Notification notification = new Notification(post, sender);
			notificationService.upsert(notification);


			/* notification for members of group */
			Group group = CacheUtils.getGroup(post.getGroupId());
			if (group != null && group.getMembers() != null) {
				for (GroupMember member : group.getMembers()) {
					if (recipientEmailSet.add(member.getEmail())) {
						user = CacheUtils.getAppUser(member.getEmail());
						if (user == null) {
							notificationRecipient = new NotificationRecipient(member.getEmail());
						} else {
							notificationRecipient = new NotificationRecipient(user);
							notificationRecipient.setSendEmail(user.getSendGroupPostEmail());
						}
						notificationRecipient.setAction(NotificationAction.POSTED_GROUP);
						notificationRecipient.setNotification(notification);
						notification.getRecipients().add(notificationRecipient);
						notificationService.upsertRecipient(notificationRecipient);
					}
				}
			}

			if (notification.getRecipients().isEmpty()) {
				notificationService.remove(notification);
			} else {
				notificationService.upsert(notification);
				BackendTaskService backendTaskService = new BackendTaskService(em);
				backendTaskService.createSendNotificationMailsTask(notification);
				backendTaskService.createSendPushNotificationTask(notification);
			}
			ScheduleService scheduleService = new ScheduleService(em);
			if(StringUtils.isBlank(group.getCalendarId())){
				scheduleService.createGroupCalendar(group);
			}
			if(StringUtils.isBlank(group.getCalendarId())){
				logger.error("cannot add event to calendar as calendar id is null for gorup : "  + group.getId() + " , " + group.getName());
			}else{
				scheduleService.createScheduleEvent(group,post);
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit afterScheduleSave");
		renderResponseRaw(true, response);
	}

	@POST
	@Path("schedule/addThumbnail")
	public void addThumbnailToSchduleFiles(@QueryParam("taskId") Long taskId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("addThumbnailToSchduleFiles, taskId : " + taskId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			TaskService taskService = new TaskService(em);
			Post post = taskService.get(taskId);
			AppUser user = CacheUtils.getAppUser(post.getCreatedBy());
			if (user.getUseGoogleDrive() ) {
				moveSchduleFilesToUserGoogleDrive(post, user, em);
			}
			Utils.sendPostToUI(post);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit addThumbnailToSchduleFiles");
		renderResponseRaw(true, response);
	}
	
	private void moveSchduleFilesToUserGoogleDrive(Post task, AppUser user, EntityManager em) {
		CommonEntityService commonService = new CommonEntityService(em);
		GoogleDriveService driveService = GoogleDriveService.getInstance();
		List<PostFile> files = task.getFiles();
		GoogleDriveFile driveFile;
		MoveFileTO moveFileTO =  MoveFileTO.getInstances().setFileOwner(user.getEmail()).setGroupId(task.getGroupId()).addParents( FOLDER.Attachments, FOLDER.Schedule).setUser(user);
		for (int index = 0 ; index < files.size(); index++) {
			PostFile postFile = files.get(index);
			if (StringUtils.isBlank(postFile.getGoogleDriveId())) {
				logger.info("upload to drive , " + postFile.getName() + " , " + postFile.getMimeType());
				try {
					driveFile = driveService.uploadFileToUserAccount(postFile, user);
					if (null != driveFile) {
						moveFileTO.addFileIds(driveFile.getId());
						driveService.renameFile(driveFile.getId(), postFile.getName(), user);
						postFile.setGoogleDriveId(driveFile.getId());
						postFile.setIcon(driveFile.getIconLink());
						postFile.setDriveLink(driveFile.getEditLink());
						postFile.setEmbedLink(driveFile.getEmbedLink());
						postFile.setUploadType(UPLOAD_TYPE.GOOGLE_DRIVE);
						commonService.upsert(postFile);
						try {
							Files.deleteIfExists(Paths.get(postFile.getPath()));
						} catch (IOException e) {
							logger.error("could not delete : " + postFile.getPath() + " , " + e.getMessage(), e);
						}
						getGroupPostFilesThumbnailFromDriveFile(driveFile, task.getGroupId(), postFile, user, commonService);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			} else if (StringUtils.isBlank(postFile.getThumbnail())) {
				driveFile = driveService.getFileFields(postFile.getGoogleDriveId(), "thumbnailLink", user);
				getGroupPostFilesThumbnailFromDriveFile(driveFile, task.getGroupId(), postFile, user, commonService);
			}else {
				logger.info("File is already on google drive , " + postFile.getName() + " , " + postFile.getMimeType());
			}
		}
		driveService.moveFile(moveFileTO);
	}
	
	@POST
	@Path("post/sendPushNotifications")
	public void sendPushNotifications(@QueryParam("notificationId") Long notificationId, @Context HttpServletResponse response) throws IOException, InterruptedException {
		logger.info("sendPushNotifications, notificationId : " + notificationId);
		EntityManager em = EntityManagerHelper.getDefaulteEntityManager();
		try {
			PostService postService = new PostService(em);

			NotificationService notificationService = new NotificationService(em);
			PushService pushService = PushService.getInstance();
			Notification notification = notificationService.get(notificationId);
			if (null != notification) {
				NotificationTO notificationTO;
				/* notification for posts */
				Post post = postService.get(notification.getEntityId());
				if (null != post) {
					Map<String, Object> json = new HashMap<>();
					json.put("name", "notification");
					CommentTO commentTO = null;
					if(NotificationType.COMMENT.equals(notification.getType()) || NotificationType.COMMENT_REPLY.equals(notification.getType())){
						PostComment comment = postService.getPostCommentById(notification.getSubEntityId());
						if (null != comment) {
							commentTO = new CommentTO(comment);
						}
					}
					for (NotificationRecipient recipient : notification.getRecipients()) {
						if (Utils.isValidEmailAddress(recipient.getEmail())) {
							notificationTO = new NotificationTO(recipient);
							notificationTO.setItem(commentTO);
							json.put("data", notificationTO);
							pushService.sendChannelMessage(recipient.getEmail(), json);
						}
					}
				}

			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (em.isOpen()) {
				em.close();
			}
		}

		logger.info("exit sendPushNotifications");
		renderResponseRaw(true, response);
	}
}
