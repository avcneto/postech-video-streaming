package com.postechvideostreaming.videostreaming.service.video;

import com.postechvideostreaming.videostreaming.domain.video.Category;
import com.postechvideostreaming.videostreaming.domain.video.Video;
import com.postechvideostreaming.videostreaming.dto.video.UpdateVideoDTO;
import com.postechvideostreaming.videostreaming.dto.video.VideoDTO;
import com.postechvideostreaming.videostreaming.exception.FailedDependencyException;
import com.postechvideostreaming.videostreaming.repository.video.VideoRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.postechvideostreaming.videostreaming.util.Validators.isNullOrBlank;
import static java.lang.String.format;

@Slf4j
@Service
public class VideoService {

  @Value("${minio.bucket}")
  private String bucketName;

  @Value("${server.port}")
  private Integer port;

  private static final String ERROR_TO_SAVE_IN_MINIO = "Error to save in minio";
  private static final String ERROR_GETTING_MINIO_URL = "Error getting minio url";
  private static final String NON_MATCHING = "non-matching fields between UserUpdateDTO and User";
  private static final String INACCESSIBLE_FIELDS = "user update has inaccessible fields";
  private static final String STREAMING_URL = "http://localhost:%s/streaming/video/%s";

  private final VideoRepository videoRepository;
  private final MinioClient minioClient;

  public VideoService(VideoRepository videoRepository, MinioClient minioClient) {
    this.videoRepository = videoRepository;
    this.minioClient = minioClient;
  }

  public Mono<VideoDTO> uploadVideo(Mono<FilePart> video, String title, String description, Category category) {
    return video
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(multipartFile -> {
              File temp = new File(multipartFile.filename());
              temp.canWrite();
              temp.canRead();

              return multipartFile.transferTo(temp)
                      .then(uploadMinio(Mono.just(temp))
                              .flatMap(minioResponse -> getMinioUrl(minioResponse.object())
                                      .map(url -> getVideoDTO(minioResponse, title, description, category))
                                      .flatMap(videoDto ->
                                              videoRepository.save(new Video(videoDto))
                                                      .flatMap(it -> Mono.just(videoDto))
                                      )
                              )
                              .doOnSuccess(videoDto -> temp.delete())
                      );
            });
  }

  public Mono<VideoDTO> updateVideoById(UpdateVideoDTO updateVideoDTO, String videoId) {
    return Mono.just(updateVideoDTO)
            .flatMap(updateVideo -> videoRepository.findById(videoId)
                    .flatMap(video -> {
                      updateVideoByUpdateVideoDTO(updateVideo, video);
                      return videoRepository.save(video)
                              .map(it -> new VideoDTO(
                                      it.getId(),
                                      it.getTitle(),
                                      it.getUrl(),
                                      it.getDescription(),
                                      it.getCategory(),
                                      it.getCreationDate(),
                                      it.getLastUpdate())
                              );
                    })
            );
  }

  public Flux<VideoDTO> getVideoByParam() {
    // TODO implements search by param
    return Flux.just(new VideoDTO("", "", "", "", Category.COMEDY, ZonedDateTime.now(),  ZonedDateTime.now()));
  }

  private VideoDTO getVideoDTO(ObjectWriteResponse minioResponse, String title,
                               String description, Category category
  ) {
    var date = ZonedDateTime.now();

    return new VideoDTO(
            minioResponse.object(),
            title,
            createUrl(minioResponse.object()),
            description,
            category,
            date,
            date
    );
  }

  private String createUrl(String objectName) {
    return format(STREAMING_URL, port, objectName);
  }

  private Mono<ObjectWriteResponse> uploadMinio(Mono<File> temp) {
    return temp.flatMap(it ->
            Mono.fromCallable(() -> minioClient.uploadObject(
                            UploadObjectArgs.builder()
                                    .bucket(bucketName)
                                    .object(UUID.randomUUID().toString())
                                    .filename(it.getAbsolutePath())
                                    .build()))
                    .onErrorMap(ex -> {
                      log.error(ERROR_GETTING_MINIO_URL, ex);
                      return new FailedDependencyException(ERROR_TO_SAVE_IN_MINIO, ex);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
    );
  }

  private Mono<String> getMinioUrl(String objectName) {
    return Mono.fromCallable(() ->
                    minioClient.getPresignedObjectUrl(
                            GetPresignedObjectUrlArgs.builder()
                                    .method(Method.GET)
                                    .bucket(bucketName)
                                    .object(objectName)
                                    .build()))
            .onErrorMap(ex -> {
              log.error(ERROR_GETTING_MINIO_URL, ex);
              return new FailedDependencyException(ERROR_GETTING_MINIO_URL, ex);
            })
            .subscribeOn(Schedulers.boundedElastic());
  }

  private void updateVideoByUpdateVideoDTO(final UpdateVideoDTO updateVideoDTO, Video video) {
    Field[] fields = UpdateVideoDTO.class.getDeclaredFields();

    for (Field field : fields) {
      field.setAccessible(true);
      Object value;
      try {
        value = field.get(updateVideoDTO);
      } catch (IllegalAccessException ex) {
        throw new FailedDependencyException(INACCESSIBLE_FIELDS, ex);
      }

      if (value != null && !isNullOrBlank(value.toString())) {
        Field correspondingField;

        try {
          correspondingField = Video.class.getDeclaredField(field.getName());
          correspondingField.setAccessible(true);
          video.setLastUpdate(ZonedDateTime.now());

          correspondingField.set(video, value);

        } catch (NoSuchFieldException ex) {
          throw new FailedDependencyException(NON_MATCHING, ex);

        } catch (IllegalAccessException ex) {
          throw new FailedDependencyException(INACCESSIBLE_FIELDS, ex);
        }
      }
    }
  }
}