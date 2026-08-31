FROM pgvector/pgvector:pg16

COPY . /docker-entrypoint-initdb.d/

