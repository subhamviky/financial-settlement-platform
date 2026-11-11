.PHONY: install build test run docker-build clean

build:
	mvn clean package -DskipTests

test:
	mvn test

run:
	mvn spring-boot:run

docker-build:
	docker build -t app:local .

clean:
	mvn clean
