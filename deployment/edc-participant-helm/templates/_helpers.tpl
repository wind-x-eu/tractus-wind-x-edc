{{- define "windx.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "windx.labels" -}}
app.kubernetes.io/name: windx-edc-participant
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "windx.postgresqlSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ .Values.secrets.name | quote }}
    items:
      - key: {{ .Values.secrets.keys.postgresPassword | quote }}
        path: postgres_password
{{- end -}}

{{- define "windx.identityhubSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ .Values.secrets.name | quote }}
    items:
      - key: {{ .Values.secrets.keys.postgresPassword | quote }}
        path: postgres_password
      - key: {{ .Values.secrets.keys.vaultToken | quote }}
        path: vault_token
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}

{{- define "windx.vaultBootstrapSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ .Values.secrets.name | quote }}
    items:
      - key: {{ .Values.secrets.keys.vaultToken | quote }}
        path: vault_token
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}

{{- define "windx.provisionSecretVolume" -}}
- name: participant-secrets
  secret:
    secretName: {{ .Values.secrets.name | quote }}
    items:
      - key: {{ .Values.secrets.keys.superuserKey | quote }}
        path: superuser_key
{{- end -}}
