#### Settings

# Video Output Storage under ZDR

Under Zero Data Retention (ZDR), generated videos must be stored in user-supplied storage. Until storage is configured, the video tools in Grok Build will return an error.

To set this up, configure an S3-compatible bucket and set the following in `~/.grok/managed_config.toml`. Grok Build presigns an upload URL for each generation and passes it to the API, so the video lands directly in your bucket and is never stored by SpaceXAI:

```toml
[tools.zdr_video_output_s3]
bucket = ""
endpoint = ""
region = ""

[tools.zdr_video_output_s3.read_write]
access_key_id = ""
secret_access_key = ""

[tools.zdr_video_output_s3.read_only]
access_key_id = ""
secret_access_key = ""
```

| Key | Required | Description |
| --- | --- | --- |
| `bucket` | yes | Bucket name for generated videos. |
| `endpoint` | yes | S3-compatible endpoint URL (AWS S3, Cloudflare R2, MinIO, …). |
| `region` | yes | Bucket region. |
| `key_prefix` | no | Object key prefix (default `grok-videos/`). |
| `expires_secs` | no | Presigned-URL lifetime in seconds (default `900`, minimum enforced). |
| `read_write` | yes | Credentials used to presign the upload URL. |
| `read_only` | no | Optional credentials used to presign a playback/download URL; omit to manage retrieval yourself. |

Only the presigned URLs leave the machine — the credentials themselves are never sent to SpaceXAI. Restart Grok Build after changing the config for it to take effect.

Note: Video tools will be enabled if the privacy setting is off (`/privacy`).
