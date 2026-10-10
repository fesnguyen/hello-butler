// The production command always requires secure cookies, even if the env file
// omitted NODE_ENV. Proxy trust must still be explicitly configured.
process.env.NODE_ENV = "production";
