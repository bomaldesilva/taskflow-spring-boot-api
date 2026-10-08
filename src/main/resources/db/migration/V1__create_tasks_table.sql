CREATE TABLE tasks (

                       id BIGSERIAL PRIMARY KEY,

                       title VARCHAR(100) NOT NULL,

                       description VARCHAR(1000),

                       status VARCHAR(30) NOT NULL

);